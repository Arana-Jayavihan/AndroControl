package main

import (
	"net"
	"testing"
	"time"
)

// TestDataBridgePipesBothWays verifies the bridge streams bytes in both directions once
// both endpoints are registered.
func TestDataBridgePipesBothWays(t *testing.T) {
	b := &dataBridge{}

	phoneSrv, phoneCli := net.Pipe() // phoneSrv = server's view, phoneCli = the "phone"
	agentSrv, agentCli := net.Pipe() // agentSrv = server's view, agentCli = the "agent"
	defer phoneCli.Close()
	defer agentCli.Close()

	b.register(rolePhone, phoneSrv)
	b.register(roleAgent, agentSrv)

	// phone -> agent
	go func() { phoneCli.Write([]byte("ping")) }()
	if got := readN(t, agentCli, 4); got != "ping" {
		t.Fatalf("phone->agent: got %q, want %q", got, "ping")
	}

	// agent -> phone
	go func() { agentCli.Write([]byte("pong")) }()
	if got := readN(t, phoneCli, 4); got != "pong" {
		t.Fatalf("agent->phone: got %q, want %q", got, "pong")
	}
}

func readN(t *testing.T, c net.Conn, n int) string {
	t.Helper()
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, n)
	got := 0
	for got < n {
		m, err := c.Read(buf[got:])
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		got += m
	}
	return string(buf)
}
