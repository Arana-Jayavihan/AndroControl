package main

import (
	"sync"
	"time"
)

const (
	// Sized above the client's maximum pointer update rate (the app's update-rate
	// slider tops out at ~250 Hz) plus headroom for occasional clicks/scrolls, so
	// legitimate input is never throttled while still bounding abuse.
	DefaultTokensPerSecond = 300.0
	DefaultBurstSize       = 400.0
)

// RateLimiter implements a token bucket rate limiter
type RateLimiter struct {
	mu         sync.Mutex
	tokens     float64
	maxTokens  float64
	refillRate float64 // tokens per second
	lastRefill time.Time
}

// NewRateLimiter creates a new rate limiter with specified rate and burst size
func NewRateLimiter(tokensPerSecond, burstSize float64) *RateLimiter {
	return &RateLimiter{
		tokens:     burstSize,
		maxTokens:  burstSize,
		refillRate: tokensPerSecond,
		lastRefill: time.Now(),
	}
}

// NewDefaultRateLimiter creates a rate limiter with default settings
func NewDefaultRateLimiter() *RateLimiter {
	return NewRateLimiter(DefaultTokensPerSecond, DefaultBurstSize)
}

// Allow checks if a request should be allowed and consumes a token if so
func (rl *RateLimiter) Allow() bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	rl.refill()

	if rl.tokens >= 1 {
		rl.tokens--
		return true
	}
	return false
}

// AllowN checks if n requests should be allowed
func (rl *RateLimiter) AllowN(n float64) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	rl.refill()

	if rl.tokens >= n {
		rl.tokens -= n
		return true
	}
	return false
}

// refill adds tokens based on elapsed time (must be called with lock held)
func (rl *RateLimiter) refill() {
	now := time.Now()
	elapsed := now.Sub(rl.lastRefill).Seconds()
	rl.lastRefill = now

	rl.tokens += elapsed * rl.refillRate
	if rl.tokens > rl.maxTokens {
		rl.tokens = rl.maxTokens
	}
}

// Tokens returns the current number of available tokens
func (rl *RateLimiter) Tokens() float64 {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	rl.refill()
	return rl.tokens
}

// ClientRateLimiters manages rate limiters per client using sync.Map
// to avoid TOCTOU race conditions in GetLimiter
type ClientRateLimiters struct {
	limiters sync.Map // map[string]*RateLimiter
}

// NewClientRateLimiters creates a new client rate limiter manager
func NewClientRateLimiters() *ClientRateLimiters {
	return &ClientRateLimiters{}
}

// GetLimiter gets or creates a rate limiter for a client
// Uses sync.Map.LoadOrStore to atomically get-or-create without race conditions
func (crl *ClientRateLimiters) GetLimiter(clientID string) *RateLimiter {
	// Try to load existing limiter first
	if limiter, ok := crl.limiters.Load(clientID); ok {
		return limiter.(*RateLimiter)
	}

	// Create new limiter and try to store it atomically
	newLimiter := NewDefaultRateLimiter()
	actual, loaded := crl.limiters.LoadOrStore(clientID, newLimiter)
	if loaded {
		// Another goroutine stored a limiter first, use that one
		return actual.(*RateLimiter)
	}
	// We stored our new limiter
	return newLimiter
}

// RemoveLimiter removes a rate limiter for a client
func (crl *ClientRateLimiters) RemoveLimiter(clientID string) {
	crl.limiters.Delete(clientID)
}

// Cleanup removes stale limiters (not accessed in the given duration)
func (crl *ClientRateLimiters) Cleanup(maxAge time.Duration) int {
	threshold := time.Now().Add(-maxAge)
	removed := 0

	// Collect keys to delete (can't delete during Range)
	var toDelete []string

	crl.limiters.Range(func(key, value interface{}) bool {
		limiter := value.(*RateLimiter)
		limiter.mu.Lock()
		isStale := limiter.lastRefill.Before(threshold)
		limiter.mu.Unlock()

		if isStale {
			toDelete = append(toDelete, key.(string))
		}
		return true
	})

	// Delete stale limiters
	for _, key := range toDelete {
		crl.limiters.Delete(key)
		removed++
	}

	return removed
}

// StartCleanup starts a background goroutine to periodically clean up stale limiters
func (crl *ClientRateLimiters) StartCleanup(interval, maxAge time.Duration, stopCh <-chan struct{}) {
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for {
			select {
			case <-ticker.C:
				crl.Cleanup(maxAge)
			case <-stopCh:
				return
			}
		}
	}()
}
