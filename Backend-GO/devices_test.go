package main

import (
	"fmt"
	"path/filepath"
	"testing"
	"time"
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

	id, err := dm.RegisterCert("client-1", "fp-aaa", "Pixel", "10.0.0.5")
	if err != nil {
		t.Fatalf("RegisterCert: %v", err)
	}
	if len(id) != DeviceIDLength*2 {
		t.Errorf("expected id of %d hex chars, got %d", DeviceIDLength*2, len(id))
	}

	if dev := dm.ValidateCert("fp-aaa"); dev == nil || dev.ID != id {
		t.Fatalf("valid cert should resolve to device %s", id)
	}
	if dm.ValidateCert("not-a-real-fp") != nil {
		t.Error("unknown cert must not validate")
	}
}

func TestDeviceCertStored(t *testing.T) {
	dm := newTestDeviceManager(t)
	id, _ := dm.RegisterCert("c", "fp-x", "Phone", "1.2.3.4")

	dm.mu.RLock()
	stored := dm.devices[id].CertFingerprint
	dm.mu.RUnlock()
	if stored != "fp-x" {
		t.Errorf("expected stored fingerprint fp-x, got %q", stored)
	}
}

func TestDeviceRevoke(t *testing.T) {
	dm := newTestDeviceManager(t)
	id, _ := dm.RegisterCert("c", "fp", "Phone", "1.2.3.4")

	if !dm.Revoke(id) {
		t.Fatal("Revoke should report success for a known id")
	}
	if dm.ValidateCert("fp") != nil {
		t.Error("revoked device cert must not validate")
	}
	if dm.Revoke("unknown-id") {
		t.Error("Revoke should report false for unknown id")
	}
}

func TestDeviceRepairReusesRecord(t *testing.T) {
	dm := newTestDeviceManager(t)

	id1, _ := dm.RegisterCert("client-abc", "fp1", "Phone", "1.2.3.4")
	id2, _ := dm.RegisterCert("client-abc", "fp2", "Phone (reinstalled)", "1.2.3.9")

	if id1 != id2 {
		t.Errorf("re-pair (same client id) should reuse the record: id1=%s id2=%s", id1, id2)
	}
	if dm.Count() != 1 {
		t.Errorf("expected 1 device after re-pair, got %d", dm.Count())
	}
	if dm.ValidateCert("fp1") != nil {
		t.Error("old cert should no longer validate after re-pair")
	}
	if dev := dm.ValidateCert("fp2"); dev == nil || dev.ID != id1 {
		t.Error("new cert should validate to the same device")
	}
}

func TestDeviceRepairAfterRevokeCreatesNew(t *testing.T) {
	dm := newTestDeviceManager(t)

	id1, _ := dm.RegisterCert("client-xyz", "fp1", "Phone", "1.2.3.4")
	dm.Revoke(id1)

	id2, _ := dm.RegisterCert("client-xyz", "fp2", "Phone", "1.2.3.4")
	if id1 == id2 {
		t.Error("re-pair after revoke should create a new device, not reuse the revoked one")
	}
	if dm.Count() != 2 {
		t.Errorf("expected 2 devices (revoked + new), got %d", dm.Count())
	}
	if dev := dm.ValidateCert("fp2"); dev == nil || dev.ID != id2 {
		t.Error("new device cert should validate")
	}
}

func TestDeviceRevokeByName(t *testing.T) {
	dm := newTestDeviceManager(t)
	dm.RegisterCert("c1", "fp1", "Pixel", "1.1.1.1")
	dm.RegisterCert("c2", "fp2", "Pixel", "1.1.1.2")
	dm.RegisterCert("c3", "fp3", "Tablet", "1.1.1.3")

	if n := dm.RevokeByName("Pixel"); n != 2 {
		t.Errorf("expected 2 revoked by name, got %d", n)
	}
	if dm.ValidateCert("fp3") == nil {
		t.Error("device with a different name should remain valid")
	}
	if dm.RevokeByName("Nonexistent") != 0 {
		t.Error("revoking an unknown name should revoke nothing")
	}
}

func TestDeviceRevokeAll(t *testing.T) {
	dm := newTestDeviceManager(t)
	dm.RegisterCert("c1", "t1", "A", "1.1.1.1")
	dm.RegisterCert("c2", "t2", "B", "1.1.1.2")

	if n := dm.RevokeAll(); n != 2 {
		t.Errorf("expected 2 revoked, got %d", n)
	}
	if dm.ValidateCert("t1") != nil || dm.ValidateCert("t2") != nil {
		t.Error("no device should validate after revoke-all")
	}
	if n := dm.RevokeAll(); n != 0 {
		t.Errorf("expected 0 on second revoke-all, got %d", n)
	}
}

func TestDeviceCleanupRevoked(t *testing.T) {
	dm := newTestDeviceManager(t)
	id1, _ := dm.RegisterCert("c1", "t1", "A", "1.1.1.1")
	dm.RegisterCert("c2", "t2live", "B", "1.1.1.2")

	dm.Revoke(id1)

	if removed := dm.CleanupRevoked(); removed != 1 {
		t.Errorf("expected 1 removed, got %d", removed)
	}
	if dm.Count() != 1 {
		t.Errorf("expected 1 device left, got %d", dm.Count())
	}
	if dm.ValidateCert("t2live") == nil {
		t.Error("active device should survive cleanup")
	}

	dm2 := &DeviceManager{devices: make(map[string]*Device), path: dm.path}
	if err := dm2.Load(); err != nil {
		t.Fatalf("Load: %v", err)
	}
	if dm2.Count() != 1 {
		t.Errorf("cleanup should persist; expected 1 after reload, got %d", dm2.Count())
	}
}

func TestDeviceRename(t *testing.T) {
	dm := newTestDeviceManager(t)
	id, _ := dm.RegisterCert("c1", "fp", "Old Name", "1.1.1.1")

	if !dm.Rename(id, "New Name") {
		t.Fatal("Rename should succeed for a known id")
	}
	dm.mu.RLock()
	got := dm.devices[id].Name
	dm.mu.RUnlock()
	if got != "New Name" {
		t.Errorf("expected renamed device, got %q", got)
	}
	if dm.Rename("nope", "x") {
		t.Error("Rename should fail for unknown id")
	}
}

func TestDevicePruneInactive(t *testing.T) {
	dm := newTestDeviceManager(t)
	staleID, _ := dm.RegisterCert("c1", "fp1", "Stale", "1.1.1.1")
	dm.RegisterCert("c2", "fp2live", "Live", "1.1.1.2")

	dm.mu.Lock()
	dm.devices[staleID].LastSeen = time.Now().Add(-48 * time.Hour)
	dm.mu.Unlock()

	if removed := dm.PruneInactive(24 * time.Hour); removed != 1 {
		t.Errorf("expected 1 pruned, got %d", removed)
	}
	if dm.ValidateCert("fp2live") == nil {
		t.Error("recently-seen device should survive pruning")
	}
}

func TestDeviceRegistryCap(t *testing.T) {
	dm := newTestDeviceManager(t)
	for i := 0; i < MaxDevices; i++ {
		if _, err := dm.RegisterCert("", fmt.Sprintf("fp-%d", i), "d", "1.1.1.1"); err != nil {
			t.Fatalf("registration %d should succeed: %v", i, err)
		}
	}
	if _, err := dm.RegisterCert("", "overflow", "d", "1.1.1.1"); err == nil {
		t.Error("registration beyond MaxDevices should fail")
	}
}

func TestDevicePersistence(t *testing.T) {
	path := filepath.Join(t.TempDir(), "devices.json")

	dm1 := &DeviceManager{devices: make(map[string]*Device), path: path}
	id, err := dm1.RegisterCert("c", "fp", "Phone", "1.2.3.4")
	if err != nil {
		t.Fatalf("RegisterCert: %v", err)
	}

	dm2 := &DeviceManager{devices: make(map[string]*Device), path: path}
	if err := dm2.Load(); err != nil {
		t.Fatalf("Load: %v", err)
	}
	if dev := dm2.ValidateCert("fp"); dev == nil || dev.ID != id {
		t.Fatal("cert should remain valid after reload")
	}

	dm2.Revoke(id)
	dm3 := &DeviceManager{devices: make(map[string]*Device), path: path}
	if err := dm3.Load(); err != nil {
		t.Fatalf("Load: %v", err)
	}
	if dm3.ValidateCert("fp") != nil {
		t.Error("revocation should persist across reload")
	}
}
