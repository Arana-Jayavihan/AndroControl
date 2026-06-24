// Command androcontrol-clip is the desktop-side clipboard agent for AndroControl.
//
// It runs in the user's graphical session and bridges the local system clipboard with
// the AndroControl server's loopback clipboard relay, so clipboard text syncs both ways
// between the desktop and the paired phone. The hardened server itself can't touch the
// display server's clipboard; this agent does, using wl-clipboard (Wayland) or xclip
// (X11).
//
// Env:
//
//	ANDROCONTROL_CLIP_HOST   relay host (default 127.0.0.1)
//	ANDROCONTROL_CLIP_PORT   relay port (default 5051; must match the server's -clip-port)
//	ANDROCONTROL_CLIP_TOKEN  shared secret matching the server's ANDROCONTROL_CLIP_TOKEN
package main

import (
	"bufio"
	"encoding/base64"
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// maxClipBytes caps the raw clipboard text we sync (must match the server).
const maxClipBytes = 1 << 20

func main() {
	log.SetFlags(0)
	host := envOr("ANDROCONTROL_CLIP_HOST", "127.0.0.1")
	port := envOr("ANDROCONTROL_CLIP_PORT", "5051")
	token := os.Getenv("ANDROCONTROL_CLIP_TOKEN")

	cb, err := detectClipboard()
	if err != nil {
		log.Fatalf("androcontrol-clip: %v", err)
	}
	log.Printf("androcontrol-clip: clipboard backend = %s", cb.name)

	addr := net.JoinHostPort(host, port)
	for {
		if err := run(addr, token, cb); err != nil {
			log.Printf("androcontrol-clip: %v; retrying in 3s", err)
		}
		time.Sleep(3 * time.Second)
	}
}

// run connects to the relay, performs the handshake, then bridges the clipboard until
// the connection drops (returning an error so main retries).
func run(addr, token string, cb *clipboard) error {
	conn, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		return fmt.Errorf("connect %s: %w", addr, err)
	}
	defer conn.Close()

	if _, err := fmt.Fprintf(conn, "AGENT:%s\n", token); err != nil {
		return err
	}
	r := bufio.NewReaderSize(conn, 1<<16)
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	ack, err := r.ReadString('\n')
	if err != nil {
		return fmt.Errorf("handshake: %w", err)
	}
	if strings.TrimSpace(ack) != "AGENT:OK" {
		return fmt.Errorf("handshake rejected: %q", strings.TrimSpace(ack))
	}
	conn.SetReadDeadline(time.Time{})
	log.Printf("androcontrol-clip: connected to relay at %s", addr)

	// `last` is the clipboard content currently known-synced; it suppresses echoes in
	// both directions (don't re-send what we just applied, don't re-apply what we sent).
	st := &syncState{}
	if cur, _ := cb.paste(); cur != "" {
		st.set(cur)
	}

	errc := make(chan error, 2)

	// Incoming: relay → local clipboard.
	go func() {
		sc := bufio.NewScanner(r)
		sc.Buffer(make([]byte, 0, 4096), 2*maxClipBytes)
		for sc.Scan() {
			line := sc.Text()
			if !strings.HasPrefix(line, "CLIP:") {
				continue
			}
			data, derr := base64.StdEncoding.DecodeString(line[len("CLIP:"):])
			if derr != nil || len(data) == 0 || len(data) > maxClipBytes {
				continue
			}
			text := string(data)
			if st.is(text) {
				continue
			}
			if cerr := cb.copy(text); cerr != nil {
				log.Printf("androcontrol-clip: set clipboard failed: %v", cerr)
				continue
			}
			// Record the round-tripped form so the poller doesn't bounce it back.
			if rt, _ := cb.paste(); rt != "" {
				st.set(rt)
			} else {
				st.set(text)
			}
		}
		errc <- fmt.Errorf("relay connection closed")
	}()

	// Outgoing: poll the local clipboard, push changes → relay.
	go func() {
		t := time.NewTicker(600 * time.Millisecond)
		defer t.Stop()
		for range t.C {
			cur, _ := cb.paste()
			if cur == "" || st.is(cur) || len(cur) > maxClipBytes {
				continue
			}
			enc := base64.StdEncoding.EncodeToString([]byte(cur))
			if _, werr := fmt.Fprintf(conn, "CLIP:%s\n", enc); werr != nil {
				errc <- werr
				return
			}
			st.set(cur)
		}
	}()

	return <-errc
}

// syncState holds the last known-synced clipboard content (guarded for the reader and
// poller goroutines).
type syncState struct {
	mu   sync.Mutex
	last string
}

func (s *syncState) set(v string) {
	s.mu.Lock()
	s.last = v
	s.mu.Unlock()
}

func (s *syncState) is(v string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return v == s.last
}

// clipboard abstracts the platform clipboard tool.
type clipboard struct {
	name     string
	copyCmd  []string // sets the clipboard, reads content from stdin
	pasteCmd []string // prints the clipboard to stdout
}

func detectClipboard() (*clipboard, error) {
	if os.Getenv("WAYLAND_DISPLAY") != "" && have("wl-copy") && have("wl-paste") {
		return &clipboard{
			name:     "wayland (wl-clipboard)",
			copyCmd:  []string{"wl-copy", "--type", "text/plain"},
			pasteCmd: []string{"wl-paste", "--no-newline", "--type", "text/plain"},
		}, nil
	}
	if os.Getenv("DISPLAY") != "" && have("xclip") {
		return &clipboard{
			name:     "x11 (xclip)",
			copyCmd:  []string{"xclip", "-selection", "clipboard", "-in"},
			pasteCmd: []string{"xclip", "-selection", "clipboard", "-out"},
		}, nil
	}
	return nil, fmt.Errorf("no supported clipboard backend found " +
		"(need wl-clipboard on Wayland or xclip on X11, and WAYLAND_DISPLAY/DISPLAY set)")
}

func (c *clipboard) paste() (string, error) {
	out, err := exec.Command(c.pasteCmd[0], c.pasteCmd[1:]...).Output()
	if err != nil {
		// Empty or non-text clipboard — treat as "no text".
		return "", nil
	}
	return string(out), nil
}

func (c *clipboard) copy(s string) error {
	cmd := exec.Command(c.copyCmd[0], c.copyCmd[1:]...)
	cmd.Stdin = strings.NewReader(s)
	return cmd.Run()
}

func have(bin string) bool {
	_, err := exec.LookPath(bin)
	return err == nil
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
