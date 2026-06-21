package main

import (
	"testing"
	"time"
)

func TestSessionCreateAndValidate(t *testing.T) {
	sm := NewSessionManager()
	s, err := sm.CreateSession("client-1")
	if err != nil {
		t.Fatalf("CreateSession: %v", err)
	}
	if s.Token == "" {
		t.Fatal("expected non-empty session token")
	}
	if sm.ValidateSession(s.Token) == nil {
		t.Error("fresh session should validate")
	}
	if sm.ValidateSession("nonexistent") != nil {
		t.Error("unknown token should not validate")
	}
}

func TestSessionExpiry(t *testing.T) {
	sm := NewSessionManager()
	s, _ := sm.CreateSession("client-1")

	// Force expiry
	sm.mu.Lock()
	sm.sessions[s.Token].ExpiresAt = time.Now().Add(-time.Minute)
	sm.mu.Unlock()

	if sm.ValidateSession(s.Token) != nil {
		t.Error("expired session should not validate")
	}
	// Expired session should have been removed
	if sm.Count() != 0 {
		t.Errorf("expected expired session removed, count=%d", sm.Count())
	}
}

func TestSessionCreateReplacesPrevious(t *testing.T) {
	sm := NewSessionManager()
	s1, _ := sm.CreateSession("client-1")
	s2, _ := sm.CreateSession("client-1")

	if sm.ValidateSession(s1.Token) != nil {
		t.Error("old session for same client should be invalidated")
	}
	if sm.ValidateSession(s2.Token) == nil {
		t.Error("new session should validate")
	}
	if sm.Count() != 1 {
		t.Errorf("expected exactly 1 session, got %d", sm.Count())
	}
}

func TestSessionRevoke(t *testing.T) {
	sm := NewSessionManager()
	s, _ := sm.CreateSession("client-1")

	sm.RevokeClientSessions("client-1")
	if sm.ValidateSession(s.Token) != nil {
		t.Error("revoked session should not validate")
	}
}

func TestSessionCleanup(t *testing.T) {
	sm := NewSessionManager()
	live, _ := sm.CreateSession("live")
	dead, _ := sm.CreateSession("dead")

	sm.mu.Lock()
	sm.sessions[dead.Token].ExpiresAt = time.Now().Add(-time.Hour)
	sm.mu.Unlock()

	removed := sm.Cleanup()
	if removed != 1 {
		t.Errorf("expected 1 removed, got %d", removed)
	}
	if sm.ValidateSession(live.Token) == nil {
		t.Error("live session should survive cleanup")
	}
}
