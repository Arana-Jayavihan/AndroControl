package main

import "testing"

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
