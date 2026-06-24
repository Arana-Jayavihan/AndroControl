package main

import (
	"bufio"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"
)

const (
	// MaxClipBytes caps the raw clipboard text we sync (1 MiB).
	MaxClipBytes = 1 << 20
	// MaxClipWire bounds the base64 wire form (plus slack for the "CLIP:" prefix).
	MaxClipWire = ((MaxClipBytes+2)/3*4) + 64
	// clipAgentAuthTimeout bounds the agent's handshake.
	clipAgentAuthTimeout = 10 * time.Second
)

// connWriter serializes writes to a net.Conn that more than one goroutine may write to
// (the device connection is written both by its own command loop and by the clipboard
// relay pushing in clips from the desktop agent).
type connWriter struct {
	mu sync.Mutex
	c  net.Conn
}

func (w *connWriter) writeString(s string) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	_, err := w.c.Write([]byte(s))
	return err
}

// ClipRelay bridges clipboard text between the active device (phone) connection and a
// desktop session agent connected over loopback. The hardened server never touches the
// real system clipboard itself — the agent does. Echoes are suppressed with a content
// hash so a clip applied on one side isn't bounced straight back.
type ClipRelay struct {
	mu       sync.Mutex
	device   *connWriter
	agent    *connWriter
	lastHash string
}

func NewClipRelay() *ClipRelay { return &ClipRelay{} }

func (r *ClipRelay) setDevice(w *connWriter) {
	r.mu.Lock()
	r.device = w
	r.mu.Unlock()
}

func (r *ClipRelay) clearDevice(w *connWriter) {
	r.mu.Lock()
	if r.device == w {
		r.device = nil
	}
	r.mu.Unlock()
}

func (r *ClipRelay) setAgent(w *connWriter) {
	r.mu.Lock()
	r.agent = w
	r.mu.Unlock()
}

func (r *ClipRelay) clearAgent(w *connWriter) {
	r.mu.Lock()
	if r.agent == w {
		r.agent = nil
	}
	r.mu.Unlock()
}

// fromDevice forwards a clip that arrived from the phone to the desktop agent.
func (r *ClipRelay) fromDevice(b64 string) { r.forward(b64, false) }

// fromAgent forwards a clip that arrived from the desktop agent to the phone.
func (r *ClipRelay) fromAgent(b64 string) { r.forward(b64, true) }

func (r *ClipRelay) forward(b64 string, toDevice bool) {
	sum := sha256.Sum256([]byte(b64))
	h := hex.EncodeToString(sum[:])

	r.mu.Lock()
	if h == r.lastHash { // echo of a clip we just synced — drop it to break the loop
		r.mu.Unlock()
		return
	}
	r.lastHash = h
	target := r.agent
	if toDevice {
		target = r.device
	}
	r.mu.Unlock()

	if target == nil {
		return
	}
	if err := target.writeString("CLIP:" + b64 + "\n"); err != nil {
		logDebug("clip relay: forward failed: %v", err)
	}
}

// startClipListener accepts the desktop clipboard agent on a loopback port and relays
// clipboard text to/from the active device. Loopback-only; an optional shared token
// (ANDROCONTROL_CLIP_TOKEN) guards against other local users connecting.
func startClipListener(addr string, port int, token string, stop <-chan struct{}) {
	ln, err := net.Listen("tcp", fmt.Sprintf("%s:%d", addr, port))
	if err != nil {
		logError("clip relay: failed to listen on %s:%d: %v", addr, port, err)
		return
	}
	if token == "" {
		logWarn("clip relay: no ANDROCONTROL_CLIP_TOKEN set — accepting any loopback agent")
	}
	logInfo("clip relay: listening on %s:%d (loopback)", addr, port)
	go func() { <-stop; ln.Close() }()

	for {
		conn, err := ln.Accept()
		if err != nil {
			return // listener closed on shutdown
		}
		go handleAgentConn(conn, token)
	}
}

func handleAgentConn(conn net.Conn, token string) {
	if !isLoopbackAddr(conn.RemoteAddr()) {
		logWarn("clip relay: rejected non-loopback connection from %s", conn.RemoteAddr())
		conn.Close()
		return
	}

	r := bufio.NewReaderSize(conn, 65536)

	// Handshake: "AGENT:<token>" (clipboard control) or "AGENT-DATA:<token>" (bulk
	// file-transfer sub-channel).
	conn.SetReadDeadline(time.Now().Add(clipAgentAuthTimeout))
	first, err := r.ReadString('\n')
	if err != nil {
		conn.Close()
		return
	}
	first = strings.TrimRight(first, "\r\n")
	conn.SetReadDeadline(time.Time{})

	// Bulk data sub-channel: hand the raw connection to the transfer bridge (which
	// owns and closes it). The transfer protocol is end-to-end with the phone.
	if _, ok := matchAgentHandshake(first, "AGENT-DATA:", token); ok {
		if _, werr := conn.Write([]byte("AGENT-DATA:OK\n")); werr != nil {
			conn.Close()
			return
		}
		logInfo("data transfer: agent bulk channel connected")
		dataBridgeInst.register(roleAgent, conn)
		return
	}

	// Clipboard control sub-channel (line-based).
	defer conn.Close()
	if _, ok := matchAgentHandshake(first, "AGENT:", token); !ok {
		logWarn("clip relay: bad agent handshake")
		return
	}

	w := &connWriter{c: conn}
	clipRelay.setAgent(w)
	defer clipRelay.clearAgent(w)
	_ = w.writeString("AGENT:OK\n")
	logInfo("clip relay: agent connected")
	defer logInfo("clip relay: agent disconnected")

	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 0, 4096), MaxClipWire)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "CLIP:") {
			b64 := line[len("CLIP:"):]
			if len(b64) == 0 || len(b64) > MaxClipWire {
				continue
			}
			clipRelay.fromAgent(b64)
		}
	}
}

// matchAgentHandshake checks a handshake line against a prefix and (if set) the token.
func matchAgentHandshake(line, prefix, token string) (rest string, ok bool) {
	if !strings.HasPrefix(line, prefix) {
		return "", false
	}
	provided := strings.TrimPrefix(line, prefix)
	if token != "" && subtle.ConstantTimeCompare([]byte(provided), []byte(token)) != 1 {
		return "", false
	}
	return provided, true
}

func isLoopbackAddr(a net.Addr) bool {
	host, _, err := net.SplitHostPort(a.String())
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}
