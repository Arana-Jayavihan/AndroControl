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
)

// Device represents a paired client device.
// The plaintext token is never stored; only its SHA-256 hash is persisted.
type Device struct {
	ID        string    `json:"id"`
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

// Register creates a new device record and returns its ID and plaintext token.
// The plaintext token is returned exactly once (to send to the client); only its
// hash is persisted.
func (dm *DeviceManager) Register(name, ip string) (id string, token string, err error) {
	dm.mu.Lock()
	defer dm.mu.Unlock()

	id, err = randomHex(DeviceIDLength)
	if err != nil {
		return "", "", err
	}
	token, err = randomHex(DeviceTokenLength)
	if err != nil {
		return "", "", err
	}

	now := time.Now()
	dev := &Device{
		ID:        id,
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
