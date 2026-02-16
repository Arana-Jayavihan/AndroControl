package main

import (
	"errors"
	"regexp"
	"strings"
	"unicode"
)

const (
	MaxMovement   = 10000
	MaxScroll     = 1000
	MaxTextLength = 1000
	MaxPayloadLen = 2048
	MaxComboKeys  = 5 // Maximum keys in a combo (e.g., CTRL+ALT+SHIFT+SUPER+KEY)
)

var (
	ErrInvalidMovement   = errors.New("movement value out of bounds")
	ErrInvalidScroll     = errors.New("scroll value out of bounds")
	ErrTextTooLong       = errors.New("text exceeds maximum length")
	ErrInvalidButton     = errors.New("invalid mouse button")
	ErrInvalidPayload    = errors.New("invalid payload format")
	ErrNullByte          = errors.New("null byte in input")
	ErrInvalidCombo      = errors.New("invalid key combo format")
	ErrDangerousSequence = errors.New("dangerous escape sequence detected")
	ErrIntegerOverflow   = errors.New("integer overflow detected")
)

// Valid key combo pattern: KEY or MOD+KEY or MOD+MOD+KEY (up to MaxComboKeys)
var keyComboPattern = regexp.MustCompile(`^[A-Z0-9]+(\+[A-Z0-9]+){0,4}$`)

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

// ValidateMovementSafe checks movement values with integer overflow protection
func ValidateMovementSafe(x, y int) error {
	// Check for potential overflow when values are used in calculations
	// int32 range: -2147483648 to 2147483647
	const maxSafe = 1000000 // Much larger than MaxMovement but safe for calculations

	if x < -maxSafe || x > maxSafe || y < -maxSafe || y > maxSafe {
		return ErrIntegerOverflow
	}

	return ValidateMovement(x, y)
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

// SanitizeTextStrict performs strict text sanitization with additional security checks
func SanitizeTextStrict(text string) (string, error) {
	if len(text) > MaxTextLength {
		return "", ErrTextTooLong
	}

	// Check for null bytes
	if strings.ContainsRune(text, '\x00') {
		return "", ErrNullByte
	}

	// Check for dangerous terminal escape sequences
	if containsDangerousEscapes(text) {
		return "", ErrDangerousSequence
	}

	// Remove control characters except newline and tab
	var sanitized strings.Builder
	sanitized.Grow(len(text))

	for _, r := range text {
		// Allow printable characters, newline, and tab
		if r == '\n' || r == '\t' || (r >= 0x20 && r < 0x7F) || (r > 0x7F && unicode.IsPrint(r)) {
			sanitized.WriteRune(r)
		}
	}

	return sanitized.String(), nil
}

// containsDangerousEscapes checks for terminal escape sequences that could be exploited
func containsDangerousEscapes(text string) bool {
	// Check for ANSI escape sequences
	if strings.Contains(text, "\x1b[") || strings.Contains(text, "\x1b]") {
		return true
	}
	// Check for other escape patterns
	if strings.Contains(text, "\x1b") {
		return true
	}
	// Check for terminal control sequences
	if strings.ContainsAny(text, "\x07\x08\x0B\x0C\x0E\x0F") {
		return true
	}
	return false
}

// ValidatePayload checks basic payload constraints
func ValidatePayload(payload string) error {
	if len(payload) > MaxPayloadLen {
		return ErrInvalidPayload
	}
	// Check for null bytes
	if strings.ContainsRune(payload, '\x00') {
		return ErrNullByte
	}
	return nil
}

// ValidateKeyCombo validates a key combination string format
// Valid formats: "CTRL+C", "CTRL+SHIFT+S", "ALT+F4", "SUPER+D"
func ValidateKeyCombo(combo string) error {
	if len(combo) == 0 || len(combo) > 50 {
		return ErrInvalidCombo
	}

	// Check for null bytes
	if strings.ContainsRune(combo, '\x00') {
		return ErrNullByte
	}

	// Convert to uppercase for validation
	upper := strings.ToUpper(combo)

	// Must match the pattern
	if !keyComboPattern.MatchString(upper) {
		return ErrInvalidCombo
	}

	// Check that we don't have too many keys
	parts := strings.Split(upper, "+")
	if len(parts) > MaxComboKeys {
		return ErrInvalidCombo
	}

	// Validate each key name
	validModifiers := map[string]bool{
		"CTRL": true, "LCTRL": true, "RCTRL": true,
		"ALT": true, "LALT": true, "RALT": true,
		"SHIFT": true, "LSHIFT": true, "RSHIFT": true,
		"SUPER": true, "WIN": true, "META": true,
	}

	validKeys := map[string]bool{
		"A": true, "B": true, "C": true, "D": true, "E": true, "F": true, "G": true,
		"H": true, "I": true, "J": true, "K": true, "L": true, "M": true, "N": true,
		"O": true, "P": true, "Q": true, "R": true, "S": true, "T": true, "U": true,
		"V": true, "W": true, "X": true, "Y": true, "Z": true,
		"0": true, "1": true, "2": true, "3": true, "4": true,
		"5": true, "6": true, "7": true, "8": true, "9": true,
		"F1": true, "F2": true, "F3": true, "F4": true, "F5": true, "F6": true,
		"F7": true, "F8": true, "F9": true, "F10": true, "F11": true, "F12": true,
		"UP": true, "DOWN": true, "LEFT": true, "RIGHT": true,
		"TAB": true, "ESC": true, "ESCAPE": true, "HOME": true, "END": true,
		"PAGEUP": true, "PAGEDOWN": true, "DELETE": true, "DEL": true,
		"INSERT": true, "INS": true, "BACKSPACE": true, "ENTER": true, "RETURN": true,
		"SPACE": true, "CAPSLOCK": true, "NUMLOCK": true, "SCROLLLOCK": true,
		"PRINTSCREEN": true, "PAUSE": true, "MENU": true,
	}

	// All keys except the last should be modifiers
	for i := 0; i < len(parts)-1; i++ {
		if !validModifiers[parts[i]] {
			return ErrInvalidCombo
		}
	}

	// Last key should be a valid key (can also be a modifier for things like CTRL+ALT)
	lastKey := parts[len(parts)-1]
	if !validKeys[lastKey] && !validModifiers[lastKey] {
		return ErrInvalidCombo
	}

	return nil
}

// ValidateCommand checks if command is in the allowed set
func ValidateCommand(command string) bool {
	validCommands := map[string]bool{
		"M":         true, // Mouse move
		"C":         true, // Click
		"S":         true, // Scroll
		"T":         true, // Type text
		"CHAR":      true, // Single character (real-time typing)
		"TB":        true, // Backspace
		"SPACE":     true, // Space
		"ENTER":     true, // Enter
		"KEY":       true, // Single key press
		"KEYDOWN":   true, // Key down (hold)
		"KEYUP":     true, // Key up (release)
		"COMBO":     true, // Key combination
		"DBLCLICK":  true, // Double click
		"MOUSEDOWN": true, // Mouse button down
		"MOUSEUP":   true, // Mouse button up
		"PING":      true, // Heartbeat
		"AUTH":      true, // Authentication
		"VERSION":   true, // Version negotiation
	}
	return validCommands[command]
}
