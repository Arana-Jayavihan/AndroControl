package main

import (
	"crypto/rand"
	"encoding/hex"
	"sync"
	"time"
)

const (
	SessionTokenLength = 32              // 32 bytes = 64 hex characters
	DefaultSessionTTL  = 30 * time.Minute
	SessionRefreshWindow = 5 * time.Minute
)

// Session represents an authenticated client session
type Session struct {
	Token     string
	ClientID  string
	CreatedAt time.Time
	ExpiresAt time.Time
	LastUsed  time.Time
}

// IsExpired checks if the session has expired
func (s *Session) IsExpired() bool {
	return time.Now().After(s.ExpiresAt)
}

// ShouldRefresh checks if the session is within the refresh window
func (s *Session) ShouldRefresh() bool {
	return time.Until(s.ExpiresAt) < SessionRefreshWindow
}

// Refresh extends the session expiration
func (s *Session) Refresh() {
	s.LastUsed = time.Now()
	s.ExpiresAt = time.Now().Add(DefaultSessionTTL)
}

// SessionManager manages client sessions
type SessionManager struct {
	mu       sync.RWMutex
	sessions map[string]*Session // token -> session
	byClient map[string]*Session // clientID -> session
	ttl      time.Duration
}

// NewSessionManager creates a new session manager
func NewSessionManager() *SessionManager {
	return &SessionManager{
		sessions: make(map[string]*Session),
		byClient: make(map[string]*Session),
		ttl:      DefaultSessionTTL,
	}
}

// CreateSession creates a new session for a client
func (sm *SessionManager) CreateSession(clientID string) (*Session, error) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	// Remove existing session for this client
	if existing, ok := sm.byClient[clientID]; ok {
		delete(sm.sessions, existing.Token)
	}

	// Generate session token
	tokenBytes := make([]byte, SessionTokenLength)
	if _, err := rand.Read(tokenBytes); err != nil {
		return nil, err
	}
	token := hex.EncodeToString(tokenBytes)

	now := time.Now()
	session := &Session{
		Token:     token,
		ClientID:  clientID,
		CreatedAt: now,
		ExpiresAt: now.Add(sm.ttl),
		LastUsed:  now,
	}

	sm.sessions[token] = session
	sm.byClient[clientID] = session

	return session, nil
}

// ValidateSession validates a session token and returns the session
// Returns nil if session is invalid or expired
func (sm *SessionManager) ValidateSession(token string) *Session {
	sm.mu.RLock()
	session, exists := sm.sessions[token]
	sm.mu.RUnlock()

	if !exists {
		return nil
	}

	if session.IsExpired() {
		sm.mu.Lock()
		delete(sm.sessions, token)
		delete(sm.byClient, session.ClientID)
		sm.mu.Unlock()
		return nil
	}

	// Update last used and refresh if needed
	sm.mu.Lock()
	session.LastUsed = time.Now()
	if session.ShouldRefresh() {
		session.Refresh()
	}
	sm.mu.Unlock()

	return session
}

// GetSessionByClient gets the session for a client
func (sm *SessionManager) GetSessionByClient(clientID string) *Session {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.byClient[clientID]
}

// RevokeSession revokes a session by token
func (sm *SessionManager) RevokeSession(token string) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if session, ok := sm.sessions[token]; ok {
		delete(sm.sessions, token)
		delete(sm.byClient, session.ClientID)
	}
}

// RevokeClientSessions revokes all sessions for a client
func (sm *SessionManager) RevokeClientSessions(clientID string) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if session, ok := sm.byClient[clientID]; ok {
		delete(sm.sessions, session.Token)
		delete(sm.byClient, clientID)
	}
}

// Cleanup removes all expired sessions
func (sm *SessionManager) Cleanup() int {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	removed := 0
	now := time.Now()

	for token, session := range sm.sessions {
		if now.After(session.ExpiresAt) {
			delete(sm.sessions, token)
			delete(sm.byClient, session.ClientID)
			removed++
		}
	}

	return removed
}

// StartCleanup starts a background goroutine to periodically clean up expired sessions
func (sm *SessionManager) StartCleanup(interval time.Duration, stopCh <-chan struct{}) {
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for {
			select {
			case <-ticker.C:
				removed := sm.Cleanup()
				if removed > 0 {
					// Optional: log cleanup
					// log.Printf("Session cleanup: removed %d expired sessions", removed)
				}
			case <-stopCh:
				return
			}
		}
	}()
}

// Count returns the number of active sessions
func (sm *SessionManager) Count() int {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return len(sm.sessions)
}

// SessionInfo returns session info without sensitive data (for logging)
type SessionInfo struct {
	ClientID  string
	CreatedAt time.Time
	ExpiresAt time.Time
	LastUsed  time.Time
}

// GetSessionInfo returns non-sensitive session info
func (sm *SessionManager) GetSessionInfo(token string) *SessionInfo {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	session, exists := sm.sessions[token]
	if !exists {
		return nil
	}

	return &SessionInfo{
		ClientID:  session.ClientID,
		CreatedAt: session.CreatedAt,
		ExpiresAt: session.ExpiresAt,
		LastUsed:  session.LastUsed,
	}
}
