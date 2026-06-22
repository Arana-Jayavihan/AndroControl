package main

import (
	"errors"
	"net"
	"sync"
)

const (
	DefaultMaxConnections = 3
	// Allow 2 per IP so a brief overlap during reconnect (e.g. config-change
	// recreate, or a dropped-then-reestablished link) isn't rejected.
	DefaultMaxPerIP = 2
)

var (
	ErrTooManyConnections = errors.New("too many connections")
	ErrTooManyFromIP      = errors.New("too many connections from this IP")
)

// ConnectionManager tracks and limits active connections
type ConnectionManager struct {
	mu           sync.Mutex
	connections  map[string]int // IP -> count
	maxTotal     int
	maxPerIP     int
	currentTotal int
}

// NewConnectionManager creates a new connection manager with default limits
func NewConnectionManager() *ConnectionManager {
	return &ConnectionManager{
		connections: make(map[string]int),
		maxTotal:    DefaultMaxConnections,
		maxPerIP:    DefaultMaxPerIP,
	}
}

// extractIP extracts the IP address from a net.Addr
func extractIP(addr net.Addr) string {
	switch v := addr.(type) {
	case *net.TCPAddr:
		return v.IP.String()
	case *net.UDPAddr:
		return v.IP.String()
	default:
		// Fallback: try to parse host:port format
		host, _, err := net.SplitHostPort(addr.String())
		if err != nil {
			return addr.String()
		}
		return host
	}
}

// TryAccept attempts to accept a new connection, returns error if limits exceeded
func (cm *ConnectionManager) TryAccept(addr net.Addr) error {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	ip := extractIP(addr)

	if cm.currentTotal >= cm.maxTotal {
		return ErrTooManyConnections
	}
	if cm.connections[ip] >= cm.maxPerIP {
		return ErrTooManyFromIP
	}

	cm.connections[ip]++
	cm.currentTotal++
	return nil
}

// Release releases a connection slot
func (cm *ConnectionManager) Release(addr net.Addr) {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	ip := extractIP(addr)

	if count, exists := cm.connections[ip]; exists {
		if count > 1 {
			cm.connections[ip]--
		} else {
			delete(cm.connections, ip)
		}
		cm.currentTotal--
	}
}
