package main

import (
	"errors"
	"strings"
	"unicode"
)

const (
	MaxMovement   = 10000
	MaxScroll     = 1000
	MaxTextLength = 1000
	MaxPayloadLen = 2048
)

var (
	ErrInvalidMovement = errors.New("movement value out of bounds")
	ErrInvalidScroll   = errors.New("scroll value out of bounds")
	ErrTextTooLong     = errors.New("text exceeds maximum length")
	ErrInvalidButton   = errors.New("invalid mouse button")
	ErrInvalidPayload  = errors.New("invalid payload format")
)

// ValidateMovement checks if x,y coordinates are within acceptable bounds
func ValidateMovement(x, y int) error {
	if x < -MaxMovement || x > MaxMovement {
		return ErrInvalidMovement
	}
	if y < -MaxMovement || y > MaxMovement {
		return ErrInvalidMovement
	}
	return nil
}

// ValidateScroll checks if scroll amount is within acceptable bounds
func ValidateScroll(amount int) error {
	if amount < -MaxScroll || amount > MaxScroll {
		return ErrInvalidScroll
	}
	return nil
}

// ValidateMouseButton checks if the button name is valid
func ValidateMouseButton(button string) error {
	switch button {
	case "left", "right", "middle":
		return nil
	default:
		return ErrInvalidButton
	}
}

// SanitizeText removes control characters and limits text length
func SanitizeText(text string) (string, error) {
	if len(text) > MaxTextLength {
		return "", ErrTextTooLong
	}

	// Remove control characters except newline and tab
	var sanitized strings.Builder
	for _, r := range text {
		if r == '\n' || r == '\t' || !unicode.IsControl(r) {
			sanitized.WriteRune(r)
		}
	}

	return sanitized.String(), nil
}

// ValidatePayload checks basic payload constraints
func ValidatePayload(payload string) error {
	if len(payload) > MaxPayloadLen {
		return ErrInvalidPayload
	}
	return nil
}

// ValidateCommand checks if command is in the allowed set
func ValidateCommand(command string) bool {
	validCommands := map[string]bool{
		"M":     true, // Mouse move
		"C":     true, // Click
		"S":     true, // Scroll
		"T":     true, // Type text
		"TB":    true, // Backspace
		"SPACE": true, // Space
		"ENTER": true, // Enter
		"PING":  true, // Heartbeat
		"AUTH":  true, // Authentication
		"VERSION": true, // Version negotiation
	}
	return validCommands[command]
}
