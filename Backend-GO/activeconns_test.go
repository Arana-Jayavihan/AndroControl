package main

import (
	"net"
	"path/filepath"
	"testing"
	"time"
)

func TestDeviceIsActive(t *testing.T) {
	dm := newTestDeviceManager(t)
	id, _ := dm.RegisterCert("c1", "fp", "Phone", "1.1.1.1")

	if !dm.IsActive(id) {
		t.Error("freshly registered device should be active")
	}
	dm.Revoke(id)
	if dm.IsActive(id) {
		t.Error("revoked device should not be active")
	}
	if dm.IsActive("unknown") {
		t.Error("unknown device should not be active")
	}
}

func TestActiveConnsCloseRevoked(t *testing.T) {
	ac := NewActiveConns()
	dm := &DeviceManager{
		devices: make(map[string]*Device),
		path:    filepath.Join(t.TempDir(), "devices.json"),
	}
	id, _ := dm.RegisterCert("c1", "fp", "Phone", "1.1.1.1")

	srv, cli := net.Pipe()
	defer cli.Close()
	ac.Add(id, srv)

	// Active device → nothing closed.
	if n := ac.CloseRevoked(dm); n != 0 {
		t.Fatalf("expected 0 closed for active device, got %d", n)
	}

	dm.Revoke(id)
	if n := ac.CloseRevoked(dm); n != 1 {
		t.Fatalf("expected 1 closed after revoke, got %d", n)
	}

	// The server side of the pipe should now be closed.
	_ = srv.SetWriteDeadline(time.Now().Add(time.Second))
	if _, err := srv.Write([]byte("x")); err == nil {
		t.Error("expected write to a closed connection to fail")
	}
}

func TestActiveConnsCloseForDevice(t *testing.T) {
	ac := NewActiveConns()
	srv1, cli1 := net.Pipe()
	srv2, cli2 := net.Pipe()
	defer cli1.Close()
	defer cli2.Close()

	ac.Add("dev", srv1)
	ac.Add("dev", srv2)

	if n := ac.CloseForDevice("dev"); n != 2 {
		t.Fatalf("expected 2 connections closed, got %d", n)
	}
	if n := ac.CloseForDevice("other"); n != 0 {
		t.Fatalf("expected 0 for unknown device, got %d", n)
	}
}

func TestActiveConnsRemove(t *testing.T) {
	ac := NewActiveConns()
	srv, cli := net.Pipe()
	defer srv.Close()
	defer cli.Close()

	ac.Add("dev", srv)
	ac.Remove("dev", srv)
	if n := ac.CloseForDevice("dev"); n != 0 {
		t.Errorf("expected 0 after removal, got %d", n)
	}
}
