package main

import (
	"testing"
	"time"
)

func TestRateLimiterBurst(t *testing.T) {
	// Zero refill rate so the burst is deterministic.
	rl := NewRateLimiter(0, 5)
	for i := 0; i < 5; i++ {
		if !rl.Allow() {
			t.Fatalf("request %d within burst should be allowed", i+1)
		}
	}
	if rl.Allow() {
		t.Error("request beyond burst should be denied")
	}
}

func TestRateLimiterAllowN(t *testing.T) {
	rl := NewRateLimiter(0, 10)
	if !rl.AllowN(10) {
		t.Error("AllowN(10) within burst should succeed")
	}
	if rl.AllowN(1) {
		t.Error("AllowN(1) after exhaustion should fail")
	}
}

func TestRateLimiterRefill(t *testing.T) {
	rl := NewRateLimiter(10, 10) // 10 tokens/sec
	// Exhaust
	if !rl.AllowN(10) {
		t.Fatal("should consume full burst")
	}
	// Simulate 1 second elapsing
	rl.mu.Lock()
	rl.lastRefill = time.Now().Add(-time.Second)
	rl.mu.Unlock()

	tokens := rl.Tokens()
	if tokens < 9 || tokens > 10 {
		t.Errorf("expected ~10 tokens after 1s refill, got %v", tokens)
	}
}

func TestClientRateLimitersIsolation(t *testing.T) {
	crl := NewClientRateLimiters()
	a := crl.GetLimiter("a")
	b := crl.GetLimiter("b")
	if a == b {
		t.Error("different clients should get different limiters")
	}
	if crl.GetLimiter("a") != a {
		t.Error("same client should get the same limiter instance")
	}
	crl.RemoveLimiter("a")
	if crl.GetLimiter("a") == a {
		t.Error("after removal a new limiter should be created")
	}
}
