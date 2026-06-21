package main

import (
	"net"
	"sync"
)

// ActiveConns tracks live authenticated connections by device ID, so that
// revoking a device can immediately drop its in-progress connection(s) rather
// than waiting for them to reconnect.
type ActiveConns struct {
	mu       sync.Mutex
	byDevice map[string]map[net.Conn]struct{}
}

func NewActiveConns() *ActiveConns {
	return &ActiveConns{byDevice: make(map[string]map[net.Conn]struct{})}
}

// Add registers an active connection for a device.
func (a *ActiveConns) Add(deviceID string, conn net.Conn) {
	if deviceID == "" {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	set := a.byDevice[deviceID]
	if set == nil {
		set = make(map[net.Conn]struct{})
		a.byDevice[deviceID] = set
	}
	set[conn] = struct{}{}
}

// Remove deregisters a connection for a device.
func (a *ActiveConns) Remove(deviceID string, conn net.Conn) {
	if deviceID == "" {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if set := a.byDevice[deviceID]; set != nil {
		delete(set, conn)
		if len(set) == 0 {
			delete(a.byDevice, deviceID)
		}
	}
}

// CloseForDevice closes all active connections for a device. Returns the count
// closed. Closing a connection unblocks its handler, which then deregisters it.
func (a *ActiveConns) CloseForDevice(deviceID string) int {
	a.mu.Lock()
	var conns []net.Conn
	if set := a.byDevice[deviceID]; set != nil {
		for c := range set {
			conns = append(conns, c)
		}
	}
	a.mu.Unlock()

	for _, c := range conns {
		c.Close()
	}
	return len(conns)
}

// CloseRevoked closes every active connection whose device is no longer active
// (revoked or removed from the registry). Returns the number of connections closed.
func (a *ActiveConns) CloseRevoked(dm *DeviceManager) int {
	a.mu.Lock()
	var conns []net.Conn
	for deviceID, set := range a.byDevice {
		if !dm.IsActive(deviceID) {
			for c := range set {
				conns = append(conns, c)
			}
		}
	}
	a.mu.Unlock()

	for _, c := range conns {
		c.Close()
	}
	return len(conns)
}
