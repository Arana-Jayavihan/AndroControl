package main

import (
	"errors"
	"strings"
	"testing"
)

func TestValidateMovement(t *testing.T) {
	cases := []struct {
		x, y    int
		wantErr bool
	}{
		{0, 0, false},
		{MaxMovement, -MaxMovement, false},
		{MaxMovement + 1, 0, true},
		{0, -MaxMovement - 1, true},
	}
	for _, c := range cases {
		err := ValidateMovement(c.x, c.y)
		if (err != nil) != c.wantErr {
			t.Errorf("ValidateMovement(%d,%d) err=%v, wantErr=%v", c.x, c.y, err, c.wantErr)
		}
	}
}

func TestValidateMovementSafe(t *testing.T) {
	if err := ValidateMovementSafe(10, 10); err != nil {
		t.Errorf("expected valid movement, got %v", err)
	}
	// In-bounds for overflow but out of MaxMovement -> ErrInvalidMovement
	if err := ValidateMovementSafe(20000, 0); !errors.Is(err, ErrInvalidMovement) {
		t.Errorf("expected ErrInvalidMovement, got %v", err)
	}
	// Beyond the overflow guard -> ErrIntegerOverflow
	if err := ValidateMovementSafe(2_000_000, 0); !errors.Is(err, ErrIntegerOverflow) {
		t.Errorf("expected ErrIntegerOverflow, got %v", err)
	}
}

func TestValidateScroll(t *testing.T) {
	if err := ValidateScroll(MaxScroll); err != nil {
		t.Errorf("expected valid scroll, got %v", err)
	}
	if err := ValidateScroll(MaxScroll + 1); !errors.Is(err, ErrInvalidScroll) {
		t.Errorf("expected ErrInvalidScroll, got %v", err)
	}
}

func TestValidateMouseButton(t *testing.T) {
	for _, b := range []string{"left", "right", "middle"} {
		if err := ValidateMouseButton(b); err != nil {
			t.Errorf("button %q should be valid, got %v", b, err)
		}
	}
	if err := ValidateMouseButton("scroll"); !errors.Is(err, ErrInvalidButton) {
		t.Errorf("expected ErrInvalidButton, got %v", err)
	}
}

func TestSanitizeTextStrict(t *testing.T) {
	if out, err := SanitizeTextStrict("Hello, World!"); err != nil || out != "Hello, World!" {
		t.Errorf("clean text mangled: out=%q err=%v", out, err)
	}

	// Null byte rejected
	if _, err := SanitizeTextStrict("a\x00b"); !errors.Is(err, ErrNullByte) {
		t.Errorf("expected ErrNullByte, got %v", err)
	}

	// ANSI escape rejected
	if _, err := SanitizeTextStrict("\x1b[31mred"); !errors.Is(err, ErrDangerousSequence) {
		t.Errorf("expected ErrDangerousSequence, got %v", err)
	}

	// Too long rejected
	if _, err := SanitizeTextStrict(strings.Repeat("a", MaxTextLength+1)); !errors.Is(err, ErrTextTooLong) {
		t.Errorf("expected ErrTextTooLong, got %v", err)
	}

	// Control chars stripped (no error), newline/tab preserved
	out, err := SanitizeTextStrict("a\x01b\tc\n")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if out != "ab\tc\n" {
		t.Errorf("control char not stripped correctly: %q", out)
	}
}

func TestValidatePayload(t *testing.T) {
	if err := ValidatePayload("normal"); err != nil {
		t.Errorf("expected valid payload, got %v", err)
	}
	if err := ValidatePayload(strings.Repeat("x", MaxPayloadLen+1)); !errors.Is(err, ErrInvalidPayload) {
		t.Errorf("expected ErrInvalidPayload, got %v", err)
	}
	if err := ValidatePayload("a\x00b"); !errors.Is(err, ErrNullByte) {
		t.Errorf("expected ErrNullByte, got %v", err)
	}
}

func TestValidateKeyCombo(t *testing.T) {
	valid := []string{"CTRL+C", "CTRL+SHIFT+S", "ALT+F4", "SUPER+D", "ctrl+c", "ENTER"}
	for _, c := range valid {
		if err := ValidateKeyCombo(c); err != nil {
			t.Errorf("combo %q should be valid, got %v", c, err)
		}
	}

	invalid := []string{"", "CTRL+", "FOO+C", "CTRL+ALT+SHIFT+SUPER+CTRL+C", "CTRL C", "A\x00B"}
	for _, c := range invalid {
		if err := ValidateKeyCombo(c); err == nil {
			t.Errorf("combo %q should be invalid", c)
		}
	}
}
