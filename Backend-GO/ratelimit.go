package main

import (
	"sync"
	"time"
)

const (
	DefaultTokensPerSecond = 100.0
	DefaultBurstSize       = 150.0
)

// RateLimiter implements a token bucket rate limiter
type RateLimiter struct {
	mu           sync.Mutex
	tokens       float64
	maxTokens    float64
	refillRate   float64 // tokens per second
	lastRefill   time.Time
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

// Reset resets the rate limiter to full capacity
func (rl *RateLimiter) Reset() {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	rl.tokens = rl.maxTokens
	rl.lastRefill = time.Now()
}

// ClientRateLimiters manages rate limiters per client
type ClientRateLimiters struct {
	mu       sync.RWMutex
	limiters map[string]*RateLimiter
}

// NewClientRateLimiters creates a new client rate limiter manager
func NewClientRateLimiters() *ClientRateLimiters {
	return &ClientRateLimiters{
		limiters: make(map[string]*RateLimiter),
	}
}

// GetLimiter gets or creates a rate limiter for a client
func (crl *ClientRateLimiters) GetLimiter(clientID string) *RateLimiter {
	crl.mu.RLock()
	limiter, exists := crl.limiters[clientID]
	crl.mu.RUnlock()

	if exists {
		return limiter
	}

	crl.mu.Lock()
	defer crl.mu.Unlock()

	// Double-check after acquiring write lock
	if limiter, exists = crl.limiters[clientID]; exists {
		return limiter
	}

	limiter = NewDefaultRateLimiter()
	crl.limiters[clientID] = limiter
	return limiter
}

// RemoveLimiter removes a rate limiter for a client
func (crl *ClientRateLimiters) RemoveLimiter(clientID string) {
	crl.mu.Lock()
	defer crl.mu.Unlock()
	delete(crl.limiters, clientID)
}

// Cleanup removes stale limiters (not accessed in the given duration)
func (crl *ClientRateLimiters) Cleanup(maxAge time.Duration) int {
	crl.mu.Lock()
	defer crl.mu.Unlock()

	threshold := time.Now().Add(-maxAge)
	removed := 0
	for id, limiter := range crl.limiters {
		limiter.mu.Lock()
		if limiter.lastRefill.Before(threshold) {
			delete(crl.limiters, id)
			removed++
		}
		limiter.mu.Unlock()
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
				removed := crl.Cleanup(maxAge)
				if removed > 0 {
					// Optional: log cleanup activity
					// log.Printf("Rate limiter cleanup: removed %d stale limiters", removed)
				}
			case <-stopCh:
				return
			}
		}
	}()
}

// Count returns the number of active rate limiters
func (crl *ClientRateLimiters) Count() int {
	crl.mu.RLock()
	defer crl.mu.RUnlock()
	return len(crl.limiters)
}
