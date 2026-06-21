package main

import "testing"

func TestParseAuthMessage(t *testing.T) {
	if tok, err := ParseAuthMessage("AUTH:abc123"); err != nil || tok != "abc123" {
		t.Errorf("got tok=%q err=%v", tok, err)
	}
	if _, err := ParseAuthMessage("AUTH:"); err == nil {
		t.Error("expected error for empty token")
	}
	if _, err := ParseAuthMessage("HELLO:abc"); err == nil {
		t.Error("expected error for non-AUTH message")
	}
}

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
