package main

import (
	"net"
	"sync"
)

// SessionGate enforces a single concurrent control session.
//
// Only one device may hold the session at a time. A connection attempt from a
// DIFFERENT device while the slot is held is rejected, leaving the active session
// undisturbed. The SAME device reconnecting (e.g. after a network drop) takes over
// its own slot, displacing its stale connection.
type SessionGate struct {
	mu       sync.Mutex
	deviceID string   // device currently holding the session ("" = free)
	conn     net.Conn // the connection holding the session
}

func NewSessionGate() *SessionGate {
	return &SessionGate{}
}

// Acquire tries to claim the session slot for (deviceID, conn).
//   - granted=true when the slot was free or already held by the same device.
//   - displaced is the previous connection to close on a same-device takeover (or nil).
//   - granted=false when a DIFFERENT device holds the session (active session kept).
func (g *SessionGate) Acquire(deviceID string, conn net.Conn) (granted bool, displaced net.Conn) {
	g.mu.Lock()
	defer g.mu.Unlock()

	if g.deviceID == "" {
		g.deviceID = deviceID
		g.conn = conn
		return true, nil
	}
	if g.deviceID == deviceID {
		old := g.conn
		g.conn = conn
		if old == conn {
			old = nil
		}
		return true, old
	}
	return false, nil // a different device holds the session
}

// Release frees the slot, but only if conn is the current holder (so a displaced
// connection's cleanup can't release the session that took it over).
func (g *SessionGate) Release(conn net.Conn) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.conn == conn {
		g.deviceID = ""
		g.conn = nil
	}
}
