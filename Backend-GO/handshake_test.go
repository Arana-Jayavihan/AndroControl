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
	sessionGate = NewSessionGate()
}

// doMTLSHandshake runs authenticateClient with a given client-cert fingerprint over
// an in-memory pipe and returns the response line(s) the server sent. The server
// speaks first (AUTH:OK / PAIR:REQUIRED / AUTH:LOCKED); when PAIR:REQUIRED is seen
// and pairLine is non-empty, it is sent and the second response collected.
func doMTLSHandshake(t *testing.T, certFP, pairLine string) []string {
	t.Helper()
	serverConn, clientConn := net.Pipe()
	defer clientConn.Close()

	go func() {
		sc := bufio.NewScanner(serverConn)
		sc.Buffer(make([]byte, 0, 4096), MaxLineLength)
		authenticateClient(serverConn, sc, certFP)
		serverConn.Close()
	}()

	_ = clientConn.SetDeadline(time.Now().Add(2 * time.Second))
	r := bufio.NewReader(clientConn)

	first, err := r.ReadString('\n')
	if err != nil {
		t.Fatalf("read first response: %v", err)
	}
	resps := []string{strings.TrimSpace(first)}

	if resps[0] == "PAIR:REQUIRED" && pairLine != "" {
		if _, err := clientConn.Write([]byte(pairLine + "\n")); err != nil {
			t.Fatalf("write pair line: %v", err)
		}
		second, err := r.ReadString('\n')
		if err != nil {
			t.Fatalf("read second response: %v", err)
		}
		resps = append(resps, strings.TrimSpace(second))
	}
	return resps
}

func TestMTLSKnownCertAuthOK(t *testing.T) {
	setupHandshakeGlobals(t)
	if _, err := deviceManager.RegisterCert("c", "known-fp", "Phone", "1.1.1.1"); err != nil {
		t.Fatalf("pre-register: %v", err)
	}
	resps := doMTLSHandshake(t, "known-fp", "")
	if len(resps) != 1 || resps[0] != "AUTH:OK" {
		t.Fatalf("expected AUTH:OK for known cert, got %v", resps)
	}
}

func TestMTLSPairThenRecognize(t *testing.T) {
	setupHandshakeGlobals(t)

	resps := doMTLSHandshake(t, "new-fp", "PAIR:enroll-secret:client-1:My Phone")
	if len(resps) != 2 || resps[0] != "PAIR:REQUIRED" || resps[1] != "PAIR:OK" {
		t.Fatalf("expected [PAIR:REQUIRED PAIR:OK], got %v", resps)
	}

	// The same cert is now recognized at the handshake.
	again := doMTLSHandshake(t, "new-fp", "")
	if len(again) != 1 || again[0] != "AUTH:OK" {
		t.Fatalf("expected AUTH:OK after pairing, got %v", again)
	}
}

func TestMTLSSecondDeviceBusy(t *testing.T) {
	setupHandshakeGlobals(t)
	deviceManager.RegisterCert("cA", "certA", "Phone A", "1.1.1.1")
	deviceManager.RegisterCert("cB", "certB", "Phone B", "1.1.1.2")

	// Device A claims the single session slot.
	if got := doMTLSHandshake(t, "certA", ""); got[0] != "AUTH:OK" {
		t.Fatalf("device A should get AUTH:OK, got %v", got)
	}
	// Device B is refused without disturbing A's session.
	if got := doMTLSHandshake(t, "certB", ""); got[0] != "AUTH:BUSY" {
		t.Fatalf("device B should get AUTH:BUSY while A holds the session, got %v", got)
	}
}

func TestMTLSSameDeviceTakeover(t *testing.T) {
	setupHandshakeGlobals(t)
	deviceManager.RegisterCert("cA", "certA", "Phone A", "1.1.1.1")

	if got := doMTLSHandshake(t, "certA", ""); got[0] != "AUTH:OK" {
		t.Fatalf("first connect should get AUTH:OK, got %v", got)
	}
	// The same device reconnecting reclaims its own slot.
	if got := doMTLSHandshake(t, "certA", ""); got[0] != "AUTH:OK" {
		t.Fatalf("same device reconnect should get AUTH:OK (takeover), got %v", got)
	}
}

func TestMTLSBadEnrollment(t *testing.T) {
	setupHandshakeGlobals(t)
	resps := doMTLSHandshake(t, "x-fp", "PAIR:wrong-token:client-1:Phone")
	if resps[len(resps)-1] != "PAIR:FAIL" {
		t.Fatalf("expected PAIR:FAIL for bad enrollment token, got %v", resps)
	}
}

func TestMTLSLockout(t *testing.T) {
	setupHandshakeGlobals(t)
	authThrottler.maxFailures = 3 // net.Pipe RemoteAddr is constant, so all share an IP

	for i := 0; i < 3; i++ {
		doMTLSHandshake(t, "bad-fp", "PAIR:wrong-token:c:Phone")
	}
	resps := doMTLSHandshake(t, "bad-fp2", "PAIR:wrong-token:c:Phone")
	if resps[0] != "AUTH:LOCKED" {
		t.Fatalf("expected AUTH:LOCKED after repeated failures, got %v", resps)
	}
}
