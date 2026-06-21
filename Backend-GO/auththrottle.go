package main

import (
	"sync"
	"time"
)

const (
	// After this many failures within the window, the IP is locked out.
	DefaultMaxAuthFailures = 5
	// Failures older than this are forgotten.
	DefaultAuthFailureWindow = 5 * time.Minute
	// How long an IP stays locked out once it trips the limit.
	DefaultAuthLockout = 15 * time.Minute
)

type authState struct {
	failures    int
	firstFail   time.Time
	lockedUntil time.Time
}

// AuthThrottler rate-limits failed authentication/pairing attempts per client IP
// to slow down brute-force / token-guessing attempts.
type AuthThrottler struct {
	mu          sync.Mutex
	byIP        map[string]*authState
	maxFailures int
	window      time.Duration
	lockout     time.Duration
}

func NewAuthThrottler() *AuthThrottler {
	return &AuthThrottler{
		byIP:        make(map[string]*authState),
		maxFailures: DefaultMaxAuthFailures,
		window:      DefaultAuthFailureWindow,
		lockout:     DefaultAuthLockout,
	}
}

// Allowed reports whether the IP may attempt auth now. When locked it also
// returns the remaining lockout duration.
func (t *AuthThrottler) Allowed(ip string) (bool, time.Duration) {
	t.mu.Lock()
	defer t.mu.Unlock()
	s := t.byIP[ip]
	if s == nil {
		return true, 0
	}
	if time.Now().Before(s.lockedUntil) {
		return false, time.Until(s.lockedUntil)
	}
	return true, 0
}

// RecordFailure registers a failed attempt for the IP and returns true if this
// failure tripped the lockout.
func (t *AuthThrottler) RecordFailure(ip string) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	now := time.Now()
	s := t.byIP[ip]
	if s == nil || now.Sub(s.firstFail) > t.window {
		s = &authState{firstFail: now}
		t.byIP[ip] = s
	}
	s.failures++
	if s.failures >= t.maxFailures {
		s.lockedUntil = now.Add(t.lockout)
		s.failures = 0
		s.firstFail = now
		return true
	}
	return false
}

// RecordSuccess clears any failure state for the IP.
func (t *AuthThrottler) RecordSuccess(ip string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	delete(t.byIP, ip)
}

// Cleanup removes stale, unlocked entries.
func (t *AuthThrottler) Cleanup() {
	t.mu.Lock()
	defer t.mu.Unlock()
	now := time.Now()
	for ip, s := range t.byIP {
		if now.After(s.lockedUntil) && now.Sub(s.firstFail) > t.window {
			delete(t.byIP, ip)
		}
	}
}

// StartCleanup periodically prunes stale throttle entries.
func (t *AuthThrottler) StartCleanup(interval time.Duration, stopCh <-chan struct{}) {
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				t.Cleanup()
			case <-stopCh:
				return
			}
		}
	}()
}
