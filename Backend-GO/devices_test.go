package main

import (
	"path/filepath"
	"testing"
)

func newTestDeviceManager(t *testing.T) *DeviceManager {
	t.Helper()
	return &DeviceManager{
		devices: make(map[string]*Device),
		path:    filepath.Join(t.TempDir(), "devices.json"),
	}
}

func TestDeviceRegisterAndValidate(t *testing.T) {
	dm := newTestDeviceManager(t)

	id, token, err := dm.Register("Pixel", "10.0.0.5")
	if err != nil {
		t.Fatalf("Register: %v", err)
	}
	if len(token) != DeviceTokenLength*2 {
		t.Errorf("expected token of %d hex chars, got %d", DeviceTokenLength*2, len(token))
	}
	if len(id) != DeviceIDLength*2 {
		t.Errorf("expected id of %d hex chars, got %d", DeviceIDLength*2, len(id))
	}

	dev := dm.ValidateToken(token)
	if dev == nil || dev.ID != id {
		t.Fatalf("valid token should resolve to device %s", id)
	}
	if dm.ValidateToken("not-a-real-token") != nil {
		t.Error("invalid token must not validate")
	}
}

func TestDeviceTokenStoredAsHash(t *testing.T) {
	dm := newTestDeviceManager(t)
	id, token, _ := dm.Register("Phone", "1.2.3.4")

	dm.mu.RLock()
	stored := dm.devices[id].TokenHash
	dm.mu.RUnlock()

	if stored == token {
		t.Error("plaintext token must not be stored")
	}
	if stored != hashToken(token) {
		t.Error("stored hash should be SHA-256 of the token")
	}
}

func TestDeviceRevoke(t *testing.T) {
	dm := newTestDeviceManager(t)
	id, token, _ := dm.Register("Phone", "1.2.3.4")

	if !dm.Revoke(id) {
		t.Fatal("Revoke should report success for a known id")
	}
	if dm.ValidateToken(token) != nil {
		t.Error("revoked device token must not validate")
	}
	if dm.Revoke("unknown-id") {
		t.Error("Revoke should report false for unknown id")
	}
}

func TestDevicePersistence(t *testing.T) {
	path := filepath.Join(t.TempDir(), "devices.json")

	dm1 := &DeviceManager{devices: make(map[string]*Device), path: path}
	id, token, err := dm1.Register("Phone", "1.2.3.4")
	if err != nil {
		t.Fatalf("Register: %v", err)
	}

	// Reload from disk into a fresh manager
	dm2 := &DeviceManager{devices: make(map[string]*Device), path: path}
	if err := dm2.Load(); err != nil {
		t.Fatalf("Load: %v", err)
	}
	if dev := dm2.ValidateToken(token); dev == nil || dev.ID != id {
		t.Fatal("token should remain valid after reload")
	}

	// Revoke persists too
	dm2.Revoke(id)
	dm3 := &DeviceManager{devices: make(map[string]*Device), path: path}
	if err := dm3.Load(); err != nil {
		t.Fatalf("Load: %v", err)
	}
	if dm3.ValidateToken(token) != nil {
		t.Error("revocation should persist across reload")
	}
}
