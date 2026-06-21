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

	id, token, err := dm.Register("", "Pixel", "10.0.0.5")
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
	id, token, _ := dm.Register("", "Phone", "1.2.3.4")

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
	id, token, _ := dm.Register("", "Phone", "1.2.3.4")

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

func TestDeviceRepairReusesRecord(t *testing.T) {
	dm := newTestDeviceManager(t)

	id1, token1, _ := dm.Register("client-abc", "Phone", "1.2.3.4")
	id2, token2, _ := dm.Register("client-abc", "Phone (renamed)", "1.2.3.9")

	// Same physical device (same client id) must reuse the record, not duplicate it.
	if id1 != id2 {
		t.Errorf("re-pair should reuse the device record: id1=%s id2=%s", id1, id2)
	}
	if dm.Count() != 1 {
		t.Errorf("expected 1 device after re-pair, got %d", dm.Count())
	}
	// Token is rotated, so the old token no longer works.
	if token1 == token2 {
		t.Error("re-pair should rotate the token")
	}
	if dm.ValidateToken(token1) != nil {
		t.Error("old token should be invalid after re-pair")
	}
	if dev := dm.ValidateToken(token2); dev == nil || dev.ID != id1 {
		t.Error("new token should validate to the same device")
	}
}

func TestDeviceRepairAfterRevokeCreatesNew(t *testing.T) {
	dm := newTestDeviceManager(t)

	id1, _, _ := dm.Register("client-xyz", "Phone", "1.2.3.4")
	dm.Revoke(id1)

	// A revoked record must NOT be reused — re-pairing creates a fresh device,
	// leaving the revoked one in place as an audit record.
	id2, token2, _ := dm.Register("client-xyz", "Phone", "1.2.3.4")
	if id1 == id2 {
		t.Error("re-pair after revoke should create a new device, not reuse the revoked one")
	}
	if dm.Count() != 2 {
		t.Errorf("expected 2 devices (revoked + new), got %d", dm.Count())
	}
	if dev := dm.ValidateToken(token2); dev == nil || dev.ID != id2 {
		t.Error("new device token should validate")
	}
}

func TestDeviceRevokeByName(t *testing.T) {
	dm := newTestDeviceManager(t)
	dm.Register("c1", "Pixel", "1.1.1.1")
	dm.Register("c2", "Pixel", "1.1.1.2") // same name, different device
	_, keepToken, _ := dm.Register("c3", "Tablet", "1.1.1.3")

	n := dm.RevokeByName("Pixel")
	if n != 2 {
		t.Errorf("expected 2 devices revoked by name, got %d", n)
	}
	if dm.ValidateToken(keepToken) == nil {
		t.Error("device with a different name should remain valid")
	}
	if dm.RevokeByName("Nonexistent") != 0 {
		t.Error("revoking an unknown name should revoke nothing")
	}
}

func TestDeviceRevokeAll(t *testing.T) {
	dm := newTestDeviceManager(t)
	_, t1, _ := dm.Register("c1", "A", "1.1.1.1")
	_, t2, _ := dm.Register("c2", "B", "1.1.1.2")

	if n := dm.RevokeAll(); n != 2 {
		t.Errorf("expected 2 revoked, got %d", n)
	}
	if dm.ValidateToken(t1) != nil || dm.ValidateToken(t2) != nil {
		t.Error("no device should validate after revoke-all")
	}
	// Calling again revokes nothing (all already revoked)
	if n := dm.RevokeAll(); n != 0 {
		t.Errorf("expected 0 on second revoke-all, got %d", n)
	}
}

func TestDeviceCleanupRevoked(t *testing.T) {
	dm := newTestDeviceManager(t)
	id1, _, _ := dm.Register("c1", "A", "1.1.1.1")
	_, liveToken, _ := dm.Register("c2", "B", "1.1.1.2")

	dm.Revoke(id1)

	removed := dm.CleanupRevoked()
	if removed != 1 {
		t.Errorf("expected 1 removed, got %d", removed)
	}
	if dm.Count() != 1 {
		t.Errorf("expected 1 device left, got %d", dm.Count())
	}
	if dm.ValidateToken(liveToken) == nil {
		t.Error("active device should survive cleanup")
	}

	// Cleanup persists across reload
	dm2 := &DeviceManager{devices: make(map[string]*Device), path: dm.path}
	if err := dm2.Load(); err != nil {
		t.Fatalf("Load: %v", err)
	}
	if dm2.Count() != 1 {
		t.Errorf("cleanup should persist; expected 1 after reload, got %d", dm2.Count())
	}
}

func TestDevicePersistence(t *testing.T) {
	path := filepath.Join(t.TempDir(), "devices.json")

	dm1 := &DeviceManager{devices: make(map[string]*Device), path: path}
	id, token, err := dm1.Register("", "Phone", "1.2.3.4")
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
