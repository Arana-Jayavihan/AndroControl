package main

import (
	"os"
	"testing"
)

func TestAuthManagerValidate(t *testing.T) {
	am := &AuthManager{token: "deadbeefdeadbeef"}

	if !am.Validate("deadbeefdeadbeef") {
		t.Error("matching token should validate")
	}
	if !am.Validate("  deadbeefdeadbeef  ") {
		t.Error("surrounding whitespace should be trimmed")
	}
	if am.Validate("wrong") {
		t.Error("mismatched token should not validate")
	}
	if am.Validate("") {
		t.Error("empty token should not validate")
	}
}

func TestAuthManagerRegenerateAndReload(t *testing.T) {
	dir := t.TempDir()
	wd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(dir); err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(wd)

	am := NewAuthManager()
	if err := am.Regenerate(); err != nil {
		t.Fatalf("Regenerate: %v", err)
	}
	tok1 := am.GetToken()
	if len(tok1) != TokenLength*2 {
		t.Fatalf("expected %d hex chars, got %d", TokenLength*2, len(tok1))
	}

	// A fresh manager must load the same token from disk via Reload.
	am2 := NewAuthManager()
	if err := am2.Reload(); err != nil {
		t.Fatalf("Reload: %v", err)
	}
	if am2.GetToken() != tok1 {
		t.Errorf("Reload got %q, want %q", am2.GetToken(), tok1)
	}

	// Regenerating again must rotate to a different token.
	if err := am.Regenerate(); err != nil {
		t.Fatalf("Regenerate (2): %v", err)
	}
	if am.GetToken() == tok1 {
		t.Error("Regenerate should produce a new token")
	}
}

func TestAuthManagerReloadMissingFileKeepsToken(t *testing.T) {
	dir := t.TempDir()
	wd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(dir); err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(wd)

	const existing = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
	am := &AuthManager{token: existing}
	if err := am.Reload(); err == nil {
		t.Error("Reload with no token file should return an error")
	}
	if am.GetToken() != existing {
		t.Error("Reload must not clear the in-memory token on failure")
	}
}
