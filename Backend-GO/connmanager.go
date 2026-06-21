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
	mu              sync.Mutex
	connections     map[string]int    // IP -> count
	maxTotal        int
	maxPerIP        int
	currentTotal    int
}

// NewConnectionManager creates a new connection manager with default limits
func NewConnectionManager() *ConnectionManager {
	return &ConnectionManager{
		connections: make(map[string]int),
		maxTotal:    DefaultMaxConnections,
		maxPerIP:    DefaultMaxPerIP,
	}
}

// NewConnectionManagerWithLimits creates a connection manager with custom limits
func NewConnectionManagerWithLimits(maxTotal, maxPerIP int) *ConnectionManager {
	return &ConnectionManager{
		connections: make(map[string]int),
		maxTotal:    maxTotal,
		maxPerIP:    maxPerIP,
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

	// Check total connections
	if cm.currentTotal >= cm.maxTotal {
		return ErrTooManyConnections
	}

	// Check per-IP limit
	if cm.connections[ip] >= cm.maxPerIP {
		return ErrTooManyFromIP
	}

	// Accept the connection
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

// CurrentConnections returns the current number of active connections
func (cm *ConnectionManager) CurrentConnections() int {
	cm.mu.Lock()
	defer cm.mu.Unlock()
	return cm.currentTotal
}

// ConnectionsFromIP returns the number of connections from a specific IP
func (cm *ConnectionManager) ConnectionsFromIP(ip string) int {
	cm.mu.Lock()
	defer cm.mu.Unlock()
	return cm.connections[ip]
}

// SetLimits updates the connection limits
func (cm *ConnectionManager) SetLimits(maxTotal, maxPerIP int) {
	cm.mu.Lock()
	defer cm.mu.Unlock()
	cm.maxTotal = maxTotal
	cm.maxPerIP = maxPerIP
}

// Stats returns connection statistics
func (cm *ConnectionManager) Stats() (total int, byIP map[string]int) {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	byIP = make(map[string]int)
	for ip, count := range cm.connections {
		byIP[ip] = count
	}
	return cm.currentTotal, byIP
}

// IsIPAllowed checks if a new connection from the IP would be allowed
func (cm *ConnectionManager) IsIPAllowed(addr net.Addr) bool {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	ip := extractIP(addr)
	return cm.connections[ip] < cm.maxPerIP && cm.currentTotal < cm.maxTotal
}
