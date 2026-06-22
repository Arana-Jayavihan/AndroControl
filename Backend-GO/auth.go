package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	TokenLength     = 32 // 32 bytes = 64 hex characters
	TokenFile       = "auth_token"
	TokenMetaFile   = "auth_token.meta"
	ConfigDir       = "."
	DefaultTokenTTL = 30 * 24 * time.Hour // 30 days
)

// TokenMetadata stores token creation time and expiration
type TokenMetadata struct {
	CreatedAt time.Time `json:"created_at"`
	ExpiresAt time.Time `json:"expires_at"`
}

// AuthManager handles token-based authentication
type AuthManager struct {
	mu       sync.RWMutex
	token    string
	metadata TokenMetadata
	maxAge   time.Duration
}

// NewAuthManager creates a new authentication manager
func NewAuthManager() *AuthManager {
	return &AuthManager{
		maxAge: DefaultTokenTTL,
	}
}

// Initialize loads or generates the authentication token
func (am *AuthManager) Initialize() error {
	am.mu.Lock()
	defer am.mu.Unlock()

	tokenPath := filepath.Join(ConfigDir, TokenFile)
	metaPath := filepath.Join(ConfigDir, TokenMetaFile)

	// Try to load existing token and metadata
	if data, err := os.ReadFile(tokenPath); err == nil {
		am.token = strings.TrimSpace(string(data))
		if len(am.token) >= TokenLength*2 { // hex encoded
			// Load metadata
			if metaData, err := os.ReadFile(metaPath); err == nil {
				if err := json.Unmarshal(metaData, &am.metadata); err != nil {
					log.Printf("Warning: failed to parse token metadata: %v", err)
					// Create metadata from file modification time
					am.createMetadataFromFile(tokenPath)
				}
			} else {
				// Create metadata from file modification time
				am.createMetadataFromFile(tokenPath)
			}

			// Check if token is expired
			if am.isTokenExpired() {
				log.Println("Authentication token has expired, regenerating...")
				return am.regenerateTokenLocked()
			}

			log.Println("Loaded existing authentication token")
			am.printExpirationInfo()
			return nil
		}
	}

	// Generate new token
	return am.regenerateTokenLocked()
}

// createMetadataFromFile creates metadata using file modification time
func (am *AuthManager) createMetadataFromFile(tokenPath string) {
	info, err := os.Stat(tokenPath)
	if err != nil {
		am.metadata = TokenMetadata{
			CreatedAt: time.Now(),
			ExpiresAt: time.Now().Add(am.maxAge),
		}
		return
	}
	am.metadata = TokenMetadata{
		CreatedAt: info.ModTime(),
		ExpiresAt: info.ModTime().Add(am.maxAge),
	}
	am.saveMetadata()
}

// isTokenExpired checks if the current token is expired
func (am *AuthManager) isTokenExpired() bool {
	return time.Now().After(am.metadata.ExpiresAt)
}

// regenerateTokenLocked generates a new token (must hold lock)
func (am *AuthManager) regenerateTokenLocked() error {
	tokenBytes := make([]byte, TokenLength)
	if _, err := rand.Read(tokenBytes); err != nil {
		return fmt.Errorf("failed to generate token: %w", err)
	}

	am.token = hex.EncodeToString(tokenBytes)
	am.metadata = TokenMetadata{
		CreatedAt: time.Now(),
		ExpiresAt: time.Now().Add(am.maxAge),
	}

	tokenPath := filepath.Join(ConfigDir, TokenFile)
	if err := os.WriteFile(tokenPath, []byte(am.token+"\n"), 0600); err != nil {
		return fmt.Errorf("failed to save token: %w", err)
	}

	if err := am.saveMetadata(); err != nil {
		log.Printf("Warning: failed to save token metadata: %v", err)
	}

	log.Println("Generated new authentication token")
	am.PrintToken()
	am.printExpirationInfo()

	return nil
}

// saveMetadata saves token metadata to file
func (am *AuthManager) saveMetadata() error {
	metaPath := filepath.Join(ConfigDir, TokenMetaFile)
	data, err := json.Marshal(am.metadata)
	if err != nil {
		return err
	}
	return os.WriteFile(metaPath, data, 0600)
}

// printExpirationInfo logs token expiration information
func (am *AuthManager) printExpirationInfo() {
	remaining := time.Until(am.metadata.ExpiresAt)
	if remaining > 24*time.Hour {
		log.Printf("Token expires in %d days", int(remaining.Hours()/24))
	} else if remaining > time.Hour {
		log.Printf("Token expires in %d hours", int(remaining.Hours()))
	} else {
		log.Printf("Token expires in %d minutes", int(remaining.Minutes()))
	}
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

// PrintToken displays the enrollment/pairing token for the operator.
func (am *AuthManager) PrintToken() {
	log.Println("╔══════════════════════════════════════════════════════════════════╗")
	log.Println("║                    ENROLLMENT / PAIRING TOKEN                    ║")
	log.Println("╠══════════════════════════════════════════════════════════════════╣")
	log.Printf("║  Token: %-58s ║", am.token)
	log.Println("╠══════════════════════════════════════════════════════════════════╣")
	log.Println("║  Use this token to PAIR a new device. Each device is then        ║")
	log.Println("║  identified by its certificate; revoke: AndroControl -revoke <id>║")
	log.Println("╚══════════════════════════════════════════════════════════════════╝")
}
