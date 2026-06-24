package main

import (
	"archive/zip"
	"bufio"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Transfer agent: bridges file transfers between the phone and the local filesystem over
// the server's bulk loopback sub-channel (AGENT-DATA). The wire protocol (end-to-end with
// the phone) is line-framed control + a raw byte payload:
//
//	SENDER -> OFFER <id> <size> <name-b64> <isZip>\n
//	RECVR  -> ACCEPT <id>\n | REJECT <id>\n
//	SENDER -> <exactly size raw bytes>
//	RECVR  -> DONE <id>\n
//
// One transfer at a time.

// maxFileBytes caps a single file transfer at 5 MB (larger transfers aren't reliable
// over Wi-Fi yet — pending resumable transfers).
const maxFileBytes = 5 << 20

type pendingSend struct {
	id     string
	file   *os.File
	name   string
	size   int64
	tmp    string // non-empty if a temp (zip) file to remove after
	result chan error
}

type transferAgent struct {
	recvDir string
	jobs    chan []string // each job: paths to send as one transfer (dir/multi -> zip)

	wmu     sync.Mutex // serializes writes to conn
	conn    net.Conn
	rd      *bufio.Reader
	pending *pendingSend
	seq     int
}

func runTransferAgent(host, port, token, recvDir string) {
	if err := os.MkdirAll(recvDir, 0o755); err != nil {
		logf("transfer: cannot create receive dir %s: %v", recvDir, err)
		return
	}
	ta := &transferAgent{recvDir: recvDir, jobs: make(chan []string, 8)}
	go ta.serveSubmitSocket()

	addr := net.JoinHostPort(host, port)
	for {
		if err := ta.session(addr, token); err != nil {
			logf("transfer: %v; retrying in 3s", err)
		}
		time.Sleep(3 * time.Second)
	}
}

func (ta *transferAgent) session(addr, token string) error {
	conn, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		return fmt.Errorf("connect %s: %w", addr, err)
	}
	defer conn.Close()

	if _, err := fmt.Fprintf(conn, "AGENT-DATA:%s\n", token); err != nil {
		return err
	}
	rd := bufio.NewReaderSize(conn, 1<<16)
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	ack, err := rd.ReadString('\n')
	if err != nil {
		return fmt.Errorf("handshake: %w", err)
	}
	if strings.TrimSpace(ack) != "AGENT-DATA:OK" {
		return fmt.Errorf("handshake rejected: %q", strings.TrimSpace(ack))
	}
	conn.SetReadDeadline(time.Time{})
	logf("transfer: bulk channel connected")

	ta.conn = conn
	ta.rd = rd
	defer func() { ta.conn = nil; ta.rd = nil; ta.failPending(fmt.Errorf("disconnected")) }()

	// Outgoing jobs: a goroutine writes OFFERs; the read loop below handles responses.
	stop := make(chan struct{})
	defer close(stop)
	go ta.sendPump(stop)

	for {
		line, err := rd.ReadString('\n')
		if err != nil {
			return fmt.Errorf("read: %w", err)
		}
		ta.handleControl(strings.TrimRight(line, "\r\n"))
	}
}

func (ta *transferAgent) write(s string) error {
	ta.wmu.Lock()
	defer ta.wmu.Unlock()
	_, err := ta.conn.Write([]byte(s))
	return err
}

// sendPump pulls outgoing jobs and writes the OFFER; the read loop completes them.
func (ta *transferAgent) sendPump(stop <-chan struct{}) {
	for {
		select {
		case <-stop:
			return
		case paths := <-ta.jobs:
			ta.startSend(paths)
		}
	}
}

func (ta *transferAgent) startSend(paths []string) {
	if ta.pending != nil {
		logf("transfer: busy, dropping send of %v", paths)
		return
	}
	f, name, isZip, size, tmp, err := prepareSend(paths)
	if err != nil {
		logf("transfer: prepare send failed: %v", err)
		return
	}
	if size > maxFileBytes {
		f.Close()
		if tmp != "" {
			os.Remove(tmp)
		}
		logf("transfer: %q is %s — over the 5 MB limit, not sending", name, humanSize(size))
		notify("AndroControl", name+" is over the 5 MB transfer limit")
		return
	}
	ta.seq++
	id := strconv.Itoa(ta.seq)
	ta.pending = &pendingSend{id: id, file: f, name: name, size: size, tmp: tmp, result: make(chan error, 1)}
	nameB64 := base64.StdEncoding.EncodeToString([]byte(name))
	if err := ta.write(fmt.Sprintf("OFFER %s %d %s %d\n", id, size, nameB64, boolToInt(isZip))); err != nil {
		ta.failPending(err)
		return
	}
	logf("transfer: offered %q (%s)", name, humanSize(size))
}

func (ta *transferAgent) handleControl(line string) {
	fields := strings.Fields(line)
	if len(fields) == 0 {
		return
	}
	switch fields[0] {
	case "OFFER":
		ta.handleIncoming(fields)
	case "ACCEPT":
		if ta.pending != nil && len(fields) >= 2 && fields[1] == ta.pending.id {
			ta.streamPending()
		}
	case "REJECT":
		if ta.pending != nil && len(fields) >= 2 && fields[1] == ta.pending.id {
			logf("transfer: phone rejected the file")
			ta.failPending(fmt.Errorf("rejected"))
		}
	}
}

func (ta *transferAgent) streamPending() {
	p := ta.pending
	pb := newProgressBar("Sending "+p.name, p.size)
	ta.wmu.Lock()
	err := copyProgress(ta.conn, p.file, p.size, pb, sendRateBps)
	ta.wmu.Unlock()
	pb.close()
	p.file.Close()
	if p.tmp != "" {
		os.Remove(p.tmp)
	}
	if err != nil {
		ta.failPending(err)
		return
	}
	// Expect a DONE line.
	done, _ := ta.rd.ReadString('\n')
	if strings.HasPrefix(strings.TrimSpace(done), "DONE") {
		logf("transfer: sent OK")
		notify("AndroControl", "File sent to phone")
		p.result <- nil
	} else {
		p.result <- fmt.Errorf("no DONE ack")
	}
	ta.pending = nil
}

func (ta *transferAgent) failPending(err error) {
	if ta.pending != nil {
		if ta.pending.file != nil {
			ta.pending.file.Close()
		}
		if ta.pending.tmp != "" {
			os.Remove(ta.pending.tmp)
		}
		select {
		case ta.pending.result <- err:
		default:
		}
		ta.pending = nil
	}
}

func (ta *transferAgent) handleIncoming(fields []string) {
	// OFFER <id> <size> <name-b64> <isZip>
	if len(fields) < 5 {
		return
	}
	id := fields[1]
	size, err := strconv.ParseInt(fields[2], 10, 64)
	if err != nil || size < 0 {
		ta.write("REJECT " + id + "\n")
		return
	}
	nameBytes, err := base64.StdEncoding.DecodeString(fields[3])
	if err != nil {
		ta.write("REJECT " + id + "\n")
		return
	}
	name := sanitizeName(string(nameBytes))
	if name == "" {
		ta.write("REJECT " + id + "\n")
		return
	}
	if size > maxFileBytes {
		logf("transfer: incoming %q is %s — over the 5 MB limit, rejecting", name, humanSize(size))
		ta.write("REJECT " + id + "\n")
		notify("AndroControl", name+" exceeds the 5 MB transfer limit")
		return
	}

	if !hasFreeSpace(ta.recvDir, size) {
		logf("transfer: not enough free space for %q (%s)", name, humanSize(size))
		ta.write("REJECT " + id + "\n")
		return
	}
	if !confirmReceive(name, size) {
		ta.write("REJECT " + id + "\n")
		return
	}
	if err := ta.write("ACCEPT " + id + "\n"); err != nil {
		return
	}

	dest, err := uniquePath(filepath.Join(ta.recvDir, name))
	if err != nil {
		// Still must drain the payload to keep the stream in sync, then bail.
		io.CopyN(io.Discard, ta.rd, size)
		return
	}
	tmp := dest + ".part"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		io.CopyN(io.Discard, ta.rd, size)
		return
	}
	pb := newProgressBar("Receiving "+name, size)
	err = copyProgress(f, ta.rd, size, pb, 0)
	pb.close()
	f.Close()
	if err != nil {
		os.Remove(tmp)
		logf("transfer: receive of %q failed: %v", name, err)
		return
	}
	if err := os.Rename(tmp, dest); err != nil {
		os.Remove(tmp)
		return
	}
	ta.write("DONE " + id + "\n")
	logf("transfer: received %q -> %s", name, dest)
	notify("AndroControl", "Received "+name)
}

// ---- send preparation (zip dirs / multiple files) ----

func prepareSend(paths []string) (f *os.File, name string, isZip bool, size int64, tmp string, err error) {
	if len(paths) == 0 {
		return nil, "", false, 0, "", fmt.Errorf("no paths")
	}
	// Single regular file -> send as-is.
	if len(paths) == 1 {
		info, statErr := os.Stat(paths[0])
		if statErr != nil {
			return nil, "", false, 0, "", statErr
		}
		if !info.IsDir() {
			file, oErr := os.Open(paths[0])
			if oErr != nil {
				return nil, "", false, 0, "", oErr
			}
			return file, filepath.Base(paths[0]), false, info.Size(), "", nil
		}
	}
	// Directory or multiple files -> zip to a temp file.
	zipName := "files.zip"
	if len(paths) == 1 {
		zipName = filepath.Base(paths[0]) + ".zip"
	}
	tmpFile, zErr := zipPaths(paths)
	if zErr != nil {
		return nil, "", false, 0, "", zErr
	}
	info, _ := tmpFile.Stat()
	if _, sErr := tmpFile.Seek(0, io.SeekStart); sErr != nil {
		tmpFile.Close()
		os.Remove(tmpFile.Name())
		return nil, "", false, 0, "", sErr
	}
	return tmpFile, zipName, true, info.Size(), tmpFile.Name(), nil
}

func zipPaths(paths []string) (*os.File, error) {
	tmp, err := os.CreateTemp("", "androcontrol-*.zip")
	if err != nil {
		return nil, err
	}
	zw := zip.NewWriter(tmp)
	for _, p := range paths {
		root := filepath.Clean(p)
		base := filepath.Base(root)
		info, err := os.Stat(root)
		if err != nil {
			continue
		}
		if !info.IsDir() {
			if err := addZipFile(zw, root, base); err != nil {
				zw.Close()
				tmp.Close()
				os.Remove(tmp.Name())
				return nil, err
			}
			continue
		}
		walkErr := filepath.Walk(root, func(path string, fi os.FileInfo, err error) error {
			if err != nil || fi.IsDir() {
				return err
			}
			rel, _ := filepath.Rel(filepath.Dir(root), path)
			return addZipFile(zw, path, rel)
		})
		if walkErr != nil {
			zw.Close()
			tmp.Close()
			os.Remove(tmp.Name())
			return nil, walkErr
		}
	}
	if err := zw.Close(); err != nil {
		tmp.Close()
		os.Remove(tmp.Name())
		return nil, err
	}
	return tmp, nil
}

func addZipFile(zw *zip.Writer, path, name string) error {
	src, err := os.Open(path)
	if err != nil {
		return err
	}
	defer src.Close()
	w, err := zw.Create(filepath.ToSlash(name))
	if err != nil {
		return err
	}
	_, err = io.Copy(w, src)
	return err
}

// ---- submit socket (androcontrol-clip send <paths...>) ----

func submitSocketPath() string {
	if dir := os.Getenv("XDG_RUNTIME_DIR"); dir != "" {
		return filepath.Join(dir, "androcontrol-clip.sock")
	}
	return filepath.Join(os.TempDir(), fmt.Sprintf("androcontrol-clip-%d.sock", os.Getuid()))
}

func (ta *transferAgent) serveSubmitSocket() {
	path := submitSocketPath()
	os.Remove(path)
	ln, err := net.Listen("unix", path)
	if err != nil {
		logf("transfer: cannot open submit socket %s: %v", path, err)
		return
	}
	defer os.Remove(path)
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go ta.handleSubmit(c)
	}
}

func (ta *transferAgent) handleSubmit(c net.Conn) {
	defer c.Close()
	sc := bufio.NewScanner(c)
	sc.Buffer(make([]byte, 0, 4096), 1<<20)
	var paths []string
	for sc.Scan() {
		p := strings.TrimSpace(sc.Text())
		if p != "" {
			paths = append(paths, p)
		}
	}
	if len(paths) == 0 {
		fmt.Fprintln(c, "ERROR no paths")
		return
	}
	if ta.conn == nil {
		fmt.Fprintln(c, "ERROR phone not connected")
		return
	}
	select {
	case ta.jobs <- paths:
		fmt.Fprintln(c, "OK queued")
	default:
		fmt.Fprintln(c, "ERROR busy")
	}
}

// runSendClient implements `androcontrol-clip send <paths...>`.
func runSendClient(args []string) int {
	if len(args) == 0 {
		fmt.Fprintln(os.Stderr, "usage: androcontrol-clip send <file|dir>...")
		return 2
	}
	c, err := net.DialTimeout("unix", submitSocketPath(), 3*time.Second)
	if err != nil {
		fmt.Fprintln(os.Stderr, "androcontrol-clip: agent not running (start the clipboard/file-transfer agent first)")
		return 1
	}
	defer c.Close()
	for _, a := range args {
		abs, err := filepath.Abs(a)
		if err != nil {
			continue
		}
		fmt.Fprintln(c, abs)
	}
	if uc, ok := c.(*net.UnixConn); ok {
		uc.CloseWrite()
	}
	resp, _ := bufio.NewReader(c).ReadString('\n')
	resp = strings.TrimSpace(resp)
	if strings.HasPrefix(resp, "OK") {
		fmt.Println("AndroControl: sending to phone…")
		return 0
	}
	fmt.Fprintln(os.Stderr, "AndroControl: "+strings.TrimPrefix(resp, "ERROR "))
	return 1
}

// ---- helpers ----

func sanitizeName(name string) string {
	b := filepath.Base(name)
	if b == "." || b == ".." || b == "" || strings.ContainsRune(b, os.PathSeparator) {
		return ""
	}
	return b
}

func uniquePath(path string) (string, error) {
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return path, nil
	}
	ext := filepath.Ext(path)
	stem := strings.TrimSuffix(path, ext)
	for i := 1; i < 1000; i++ {
		cand := fmt.Sprintf("%s (%d)%s", stem, i, ext)
		if _, err := os.Stat(cand); os.IsNotExist(err) {
			return cand, nil
		}
	}
	return "", fmt.Errorf("too many name collisions")
}

func hasFreeSpace(dir string, need int64) bool {
	var st syscall.Statfs_t
	if err := syscall.Statfs(dir, &st); err != nil {
		return true // can't tell; let it try
	}
	avail := int64(st.Bavail) * int64(st.Bsize)
	return avail > need+(64<<20) // keep 64 MB headroom
}

func confirmReceive(name string, size int64) bool {
	msg := fmt.Sprintf("Receive \"%s\" (%s) from your phone?", name, humanSize(size))
	if p, _ := exec.LookPath("zenity"); p != "" {
		return exec.Command(p, "--question", "--title=AndroControl", "--text="+msg).Run() == nil
	}
	if p, _ := exec.LookPath("kdialog"); p != "" {
		return exec.Command(p, "--yesno", msg, "--title", "AndroControl").Run() == nil
	}
	logf("transfer: no zenity/kdialog found — rejecting %q (install one to accept files)", name)
	return false
}

func notify(title, body string) {
	if p, _ := exec.LookPath("notify-send"); p != "" {
		_ = exec.Command(p, "-a", "AndroControl", title, body).Run()
	}
}

func humanSize(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for x := n / unit; x >= unit; x /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(n)/float64(div), "KMGTPE"[exp])
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// progressBar drives a zenity --progress dialog (no-op if zenity is absent).
type progressBar struct {
	stdin io.WriteCloser
	cmd   *exec.Cmd
	total int64
	last  int
}

func newProgressBar(title string, total int64) *progressBar {
	pb := &progressBar{total: total}
	z, err := exec.LookPath("zenity")
	if err != nil {
		return pb
	}
	cmd := exec.Command(z, "--progress", "--auto-close", "--no-cancel",
		"--title=AndroControl", "--text="+title, "--width=360")
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return pb
	}
	if err := cmd.Start(); err != nil {
		return pb
	}
	pb.stdin = stdin
	pb.cmd = cmd
	return pb
}

func (pb *progressBar) update(done int64) {
	if pb.stdin == nil || pb.total <= 0 {
		return
	}
	pct := int(done * 100 / pb.total)
	if pct <= pb.last {
		return
	}
	pb.last = pct
	fmt.Fprintf(pb.stdin, "%d\n", pct)
}

func (pb *progressBar) close() {
	if pb.stdin != nil {
		pb.stdin.Close()
		pb.stdin = nil
	}
	if pb.cmd != nil {
		pb.cmd.Wait()
		pb.cmd = nil
	}
}

// sendRateBps paces outbound transfers (desktop -> phone) to leave the link headroom.
const sendRateBps = 4 * 1024 * 1024

// copyProgress copies exactly total bytes from src to dst, updating the progress bar and
// keeping the byte stream framed (never reads past total). If rateBps > 0 it paces the
// copy to that rate (used for sending; 0 = unlimited, for receiving).
func copyProgress(dst io.Writer, src io.Reader, total int64, pb *progressBar, rateBps int64) error {
	buf := make([]byte, 64*1024)
	var done int64
	start := time.Now()
	for done < total {
		want := int64(len(buf))
		if total-done < want {
			want = total - done
		}
		n, err := src.Read(buf[:int(want)])
		if n > 0 {
			if _, werr := dst.Write(buf[:n]); werr != nil {
				return werr
			}
			done += int64(n)
			pb.update(done)
			if rateBps > 0 {
				allowed := time.Duration(done * int64(time.Second) / rateBps)
				if d := allowed - time.Since(start); d > 0 {
					time.Sleep(d)
				}
			}
		}
		if err != nil {
			if err == io.EOF && done == total {
				return nil
			}
			return err
		}
	}
	return nil
}
