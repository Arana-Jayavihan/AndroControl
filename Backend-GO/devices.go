package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	DeviceTokenLength = 32 // 32 bytes -> 64 hex characters
	DeviceIDLength    = 16 // 16 bytes -> 32 hex characters
	DevicesFile       = "devices.json"
	MaxDeviceNameLen  = 64

	// DeviceCleanupInterval is how often revoked devices are pruned automatically.
	DeviceCleanupInterval = 24 * time.Hour
)

// Device represents a paired client device.
// The plaintext token is never stored; only its SHA-256 hash is persisted.
type Device struct {
	ID        string    `json:"id"`
	ClientID  string    `json:"client_id,omitempty"` // stable per-install id from the client
	Name      string    `json:"name"`
	TokenHash string    `json:"token_hash"`
	CreatedAt time.Time `json:"created_at"`
	LastSeen  time.Time `json:"last_seen"`
	LastIP    string    `json:"last_ip"`
	Revoked   bool      `json:"revoked"`
}

// DeviceManager manages the registry of paired devices.
type DeviceManager struct {
	mu      sync.RWMutex
	devices map[string]*Device // id -> device
	path    string
}

// NewDeviceManager creates a device manager backed by the default devices file.
func NewDeviceManager() *DeviceManager {
	return &DeviceManager{
		devices: make(map[string]*Device),
		path:    filepath.Join(ConfigDir, DevicesFile),
	}
}

// Load reads the device registry from disk. A missing file is not an error.
func (dm *DeviceManager) Load() error {
	dm.mu.Lock()
	defer dm.mu.Unlock()

	data, err := os.ReadFile(dm.path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	var list []*Device
	if err := json.Unmarshal(data, &list); err != nil {
		return fmt.Errorf("failed to parse devices file: %w", err)
	}
	for _, d := range list {
		dm.devices[d.ID] = d
	}
	log.Printf("Loaded %d paired device(s)", len(dm.devices))
	return nil
}

// Reload re-reads the registry from disk, replacing the in-memory state. Used to
// pick up out-of-band changes (e.g. `AndroControl -revoke` run against a service).
func (dm *DeviceManager) Reload() error {
	dm.mu.Lock()
	defer dm.mu.Unlock()

	data, err := os.ReadFile(dm.path)
	if err != nil {
		if os.IsNotExist(err) {
			dm.devices = make(map[string]*Device)
			return nil
		}
		return err
	}

	var list []*Device
	if err := json.Unmarshal(data, &list); err != nil {
		return fmt.Errorf("failed to parse devices file: %w", err)
	}

	fresh := make(map[string]*Device, len(list))
	for _, d := range list {
		fresh[d.ID] = d
	}
	dm.devices = fresh
	log.Printf("Reloaded device registry (%d device(s))", len(fresh))
	return nil
}

// saveLocked persists the registry atomically (caller must hold the lock).
func (dm *DeviceManager) saveLocked() error {
	list := make([]*Device, 0, len(dm.devices))
	for _, d := range dm.devices {
		list = append(list, d)
	}

	data, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return err
	}

	tmp := dm.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, dm.path)
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// sanitizeDeviceName strips control characters and bounds the length.
func sanitizeDeviceName(name string) string {
	cleaned := make([]rune, 0, len(name))
	for _, r := range name {
		if r == '\n' || r == '\r' || r < 0x20 {
			continue
		}
		cleaned = append(cleaned, r)
	}
	s := strings.TrimSpace(string(cleaned))
	if s == "" {
		s = "Unknown device"
	}
	if len(s) > MaxDeviceNameLen {
		s = s[:MaxDeviceNameLen]
	}
	return s
}

// Register pairs a device and returns its ID and plaintext token. The plaintext
// token is returned exactly once (to send to the client); only its hash is stored.
//
// If clientID is non-empty and matches an existing, non-revoked device, that
// record is reused and its token is rotated in place — this prevents a duplicate
// entry every time the same physical device re-pairs (e.g. after the server was
// removed and re-added in the app). A revoked record is never reused, so
// revocation cannot be silently undone by re-pairing.
func (dm *DeviceManager) Register(clientID, name, ip string) (id string, token string, err error) {
	dm.mu.Lock()
	defer dm.mu.Unlock()

	now := time.Now()

	if clientID != "" {
		for _, d := range dm.devices {
			if d.ClientID == clientID && !d.Revoked {
				token, err = randomHex(DeviceTokenLength)
				if err != nil {
					return "", "", err
				}
				d.TokenHash = hashToken(token)
				d.Name = sanitizeDeviceName(name)
				d.LastSeen = now
				d.LastIP = ip
				if err := dm.saveLocked(); err != nil {
					return "", "", err
				}
				log.Printf("Re-paired existing device %q (%s) from %s", d.Name, d.ID, ip)
				return d.ID, token, nil
			}
		}
	}

	id, err = randomHex(DeviceIDLength)
	if err != nil {
		return "", "", err
	}
	token, err = randomHex(DeviceTokenLength)
	if err != nil {
		return "", "", err
	}

	dev := &Device{
		ID:        id,
		ClientID:  clientID,
		Name:      sanitizeDeviceName(name),
		TokenHash: hashToken(token),
		CreatedAt: now,
		LastSeen:  now,
		LastIP:    ip,
		Revoked:   false,
	}
	dm.devices[id] = dev

	if err := dm.saveLocked(); err != nil {
		delete(dm.devices, id)
		return "", "", err
	}

	log.Printf("Registered new device %q (%s) from %s", dev.Name, dev.ID, ip)
	return id, token, nil
}

// ValidateToken returns the (non-revoked) device matching the token, or nil.
// The comparison is constant-time and scans the whole registry to avoid leaking
// which entry matched via timing.
func (dm *DeviceManager) ValidateToken(token string) *Device {
	dm.mu.RLock()
	defer dm.mu.RUnlock()

	incoming := hashToken(token)
	var match *Device
	for _, d := range dm.devices {
		if d.Revoked {
			continue
		}
		if subtle.ConstantTimeCompare([]byte(d.TokenHash), []byte(incoming)) == 1 {
			match = d
		}
	}
	return match
}

// Touch updates the last-seen timestamp and IP for a device.
func (dm *DeviceManager) Touch(id, ip string) {
	dm.mu.Lock()
	defer dm.mu.Unlock()
	if d, ok := dm.devices[id]; ok {
		d.LastSeen = time.Now()
		d.LastIP = ip
		if err := dm.saveLocked(); err != nil {
			log.Printf("Warning: failed to persist device last-seen: %v", err)
		}
	}
}

// Revoke marks a device as revoked. Returns false if the ID is unknown.
func (dm *DeviceManager) Revoke(id string) bool {
	dm.mu.Lock()
	defer dm.mu.Unlock()
	if d, ok := dm.devices[id]; ok {
		if d.Revoked {
			return true
		}
		d.Revoked = true
		if err := dm.saveLocked(); err != nil {
			log.Printf("Warning: failed to persist device revocation: %v", err)
		}
		log.Printf("Revoked device %q (%s)", d.Name, d.ID)
		return true
	}
	return false
}

// RevokeByName marks all non-revoked devices with the given name as revoked.
// Returns the number of devices revoked.
func (dm *DeviceManager) RevokeByName(name string) int {
	dm.mu.Lock()
	defer dm.mu.Unlock()
	count := 0
	for _, d := range dm.devices {
		if !d.Revoked && d.Name == name {
			d.Revoked = true
			count++
		}
	}
	if count > 0 {
		if err := dm.saveLocked(); err != nil {
			log.Printf("Warning: failed to persist revoke-by-name: %v", err)
		}
		log.Printf("Revoked %d device(s) named %q", count, name)
	}
	return count
}

// RevokeAll marks every non-revoked device as revoked.
// Returns the number of devices revoked.
func (dm *DeviceManager) RevokeAll() int {
	dm.mu.Lock()
	defer dm.mu.Unlock()
	count := 0
	for _, d := range dm.devices {
		if !d.Revoked {
			d.Revoked = true
			count++
		}
	}
	if count > 0 {
		if err := dm.saveLocked(); err != nil {
			log.Printf("Warning: failed to persist revoke-all: %v", err)
		}
		log.Printf("Revoked all devices (%d)", count)
	}
	return count
}

// Rename changes a device's display name. Returns false if the ID is unknown.
func (dm *DeviceManager) Rename(id, name string) bool {
	dm.mu.Lock()
	defer dm.mu.Unlock()
	if d, ok := dm.devices[id]; ok {
		d.Name = sanitizeDeviceName(name)
		if err := dm.saveLocked(); err != nil {
			log.Printf("Warning: failed to persist device rename: %v", err)
		}
		return true
	}
	return false
}

// PruneInactive permanently removes devices not seen within maxAge.
// Returns the number of records removed.
func (dm *DeviceManager) PruneInactive(maxAge time.Duration) int {
	dm.mu.Lock()
	defer dm.mu.Unlock()
	cutoff := time.Now().Add(-maxAge)
	removed := 0
	for id, d := range dm.devices {
		if d.LastSeen.Before(cutoff) {
			delete(dm.devices, id)
			removed++
		}
	}
	if removed > 0 {
		if err := dm.saveLocked(); err != nil {
			log.Printf("Warning: failed to persist inactive-device prune: %v", err)
		}
	}
	return removed
}

// CleanupRevoked permanently removes revoked devices from the registry.
// Returns the number of records removed.
func (dm *DeviceManager) CleanupRevoked() int {
	dm.mu.Lock()
	defer dm.mu.Unlock()
	removed := 0
	for id, d := range dm.devices {
		if d.Revoked {
			delete(dm.devices, id)
			removed++
		}
	}
	if removed > 0 {
		if err := dm.saveLocked(); err != nil {
			log.Printf("Warning: failed to persist device cleanup: %v", err)
		}
	}
	return removed
}

// StartCleanup periodically prunes revoked devices from the registry.
func (dm *DeviceManager) StartCleanup(interval time.Duration, stopCh <-chan struct{}) {
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				if removed := dm.CleanupRevoked(); removed > 0 {
					log.Printf("Device cleanup: removed %d revoked device(s)", removed)
				}
			case <-stopCh:
				return
			}
		}
	}()
}

// List returns a snapshot copy of all devices.
func (dm *DeviceManager) List() []Device {
	dm.mu.RLock()
	defer dm.mu.RUnlock()
	out := make([]Device, 0, len(dm.devices))
	for _, d := range dm.devices {
		out = append(out, *d)
	}
	return out
}

// Count returns the number of registered (including revoked) devices.
func (dm *DeviceManager) Count() int {
	dm.mu.RLock()
	defer dm.mu.RUnlock()
	return len(dm.devices)
}
