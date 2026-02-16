package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

const (
	TokenLength  = 32 // 32 bytes = 64 hex characters
	TokenFile    = "auth_token"
	ConfigDir    = "."
)

// AuthManager handles token-based authentication
type AuthManager struct {
	mu    sync.RWMutex
	token string
}

// NewAuthManager creates a new authentication manager
func NewAuthManager() *AuthManager {
	return &AuthManager{}
}

// Initialize loads or generates the authentication token
func (am *AuthManager) Initialize() error {
	am.mu.Lock()
	defer am.mu.Unlock()

	tokenPath := filepath.Join(ConfigDir, TokenFile)

	// Try to load existing token
	if data, err := os.ReadFile(tokenPath); err == nil {
		am.token = strings.TrimSpace(string(data))
		if len(am.token) >= TokenLength*2 { // hex encoded
			log.Println("Loaded existing authentication token")
			return nil
		}
	}

	// Generate new token
	tokenBytes := make([]byte, TokenLength)
	if _, err := rand.Read(tokenBytes); err != nil {
		return fmt.Errorf("failed to generate token: %w", err)
	}

	am.token = hex.EncodeToString(tokenBytes)

	// Save token to file
	if err := os.WriteFile(tokenPath, []byte(am.token+"\n"), 0600); err != nil {
		return fmt.Errorf("failed to save token: %w", err)
	}

	log.Println("Generated new authentication token")
	am.PrintToken()

	return nil
}

// Validate checks if the provided token matches
func (am *AuthManager) Validate(providedToken string) bool {
	am.mu.RLock()
	defer am.mu.RUnlock()

	// Use constant-time comparison to prevent timing attacks
	return subtle.ConstantTimeCompare([]byte(am.token), []byte(strings.TrimSpace(providedToken))) == 1
}

// GetToken returns the current token (for display purposes)
func (am *AuthManager) GetToken() string {
	am.mu.RLock()
	defer am.mu.RUnlock()
	return am.token
}

// RegenerateToken creates a new token
func (am *AuthManager) RegenerateToken() error {
	am.mu.Lock()
	defer am.mu.Unlock()

	tokenBytes := make([]byte, TokenLength)
	if _, err := rand.Read(tokenBytes); err != nil {
		return fmt.Errorf("failed to generate token: %w", err)
	}

	am.token = hex.EncodeToString(tokenBytes)

	tokenPath := filepath.Join(ConfigDir, TokenFile)
	if err := os.WriteFile(tokenPath, []byte(am.token+"\n"), 0600); err != nil {
		return fmt.Errorf("failed to save token: %w", err)
	}

	log.Println("Regenerated authentication token")
	am.PrintToken()

	return nil
}

// PrintToken displays the token for the user
func (am *AuthManager) PrintToken() {
	log.Println("=== Authentication Token ===")
	log.Printf("Token: %s", am.token)
	log.Println("Enter this token in the Android app when adding the server")
	log.Println("============================")
}

// AuthResult represents the result of an authentication attempt
type AuthResult int

const (
	AuthSuccess AuthResult = iota
	AuthFailed
	AuthTimeout
	AuthInvalidFormat
)

func (r AuthResult) String() string {
	switch r {
	case AuthSuccess:
		return "AUTH:OK"
	case AuthFailed:
		return "AUTH:FAIL"
	case AuthTimeout:
		return "AUTH:TIMEOUT"
	case AuthInvalidFormat:
		return "AUTH:INVALID"
	default:
		return "AUTH:ERROR"
	}
}

// ParseAuthMessage extracts the token from an AUTH message
func ParseAuthMessage(message string) (string, error) {
	message = strings.TrimSpace(message)

	// Expected format: "AUTH:<token>"
	if !strings.HasPrefix(message, "AUTH:") {
		return "", fmt.Errorf("invalid auth message format")
	}

	token := strings.TrimPrefix(message, "AUTH:")
	if len(token) == 0 {
		return "", fmt.Errorf("empty token")
	}

	return token, nil
}
