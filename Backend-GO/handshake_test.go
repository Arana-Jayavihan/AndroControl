package main

import (
	"bufio"
	"net"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// setupHandshakeGlobals wires up the package globals authenticateClient relies on,
// using an in-memory device registry under a temp dir.
func setupHandshakeGlobals(t *testing.T) {
	t.Helper()
	authManager = &AuthManager{token: "enroll-secret"}
	deviceManager = &DeviceManager{
		devices: make(map[string]*Device),
		path:    filepath.Join(t.TempDir(), "devices.json"),
	}
	authThrottler = NewAuthThrottler()
}

// doHandshake runs authenticateClient against an in-memory pipe and returns the
// single-line response the server sends back.
func doHandshake(t *testing.T, request string) string {
	t.Helper()
	serverConn, clientConn := net.Pipe()
	defer clientConn.Close()

	go func() {
		authenticateClient(serverConn, bufio.NewReader(serverConn))
		serverConn.Close()
	}()

	if err := clientConn.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("set deadline: %v", err)
	}
	// Write concurrently with the read: on the lockout path the server replies
	// without consuming the request, and net.Pipe is unbuffered (a real TCP
	// socket buffers the write).
	go func() {
		_, _ = clientConn.Write([]byte(request))
	}()
	resp, err := bufio.NewReader(clientConn).ReadString('\n')
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	return strings.TrimSpace(resp)
}

func TestHandshakePairThenAuth(t *testing.T) {
	setupHandshakeGlobals(t)

	resp := doHandshake(t, "PAIR:enroll-secret:client-1:My Phone\n")
	if !strings.HasPrefix(resp, "PAIR:OK:") {
		t.Fatalf("expected PAIR:OK, got %q", resp)
	}
	parts := strings.Split(resp, ":")
	if len(parts) != 4 {
		t.Fatalf("expected PAIR:OK:<id>:<token>, got %q", resp)
	}
	deviceToken := parts[3]

	if got := doHandshake(t, "AUTH:"+deviceToken+"\n"); got != "AUTH:OK" {
		t.Fatalf("expected AUTH:OK, got %q", got)
	}
}

func TestHandshakeBadEnrollment(t *testing.T) {
	setupHandshakeGlobals(t)
	if got := doHandshake(t, "PAIR:wrong-token:client-1:Phone\n"); got != "PAIR:FAIL" {
		t.Fatalf("expected PAIR:FAIL, got %q", got)
	}
}

func TestHandshakeUnknownDevice(t *testing.T) {
	setupHandshakeGlobals(t)
	if got := doHandshake(t, "AUTH:deadbeef\n"); got != "AUTH:FAIL" {
		t.Fatalf("expected AUTH:FAIL, got %q", got)
	}
}

func TestHandshakeLockout(t *testing.T) {
	setupHandshakeGlobals(t)
	authThrottler.maxFailures = 3 // net.Pipe RemoteAddr is constant, so all share an IP

	for i := 0; i < 3; i++ {
		doHandshake(t, "AUTH:bad\n")
	}
	if got := doHandshake(t, "AUTH:bad\n"); got != "AUTH:LOCKED" {
		t.Fatalf("expected AUTH:LOCKED after repeated failures, got %q", got)
	}
}
