package main

import (
	"testing"
	"time"
)

func TestAuthThrottlerLockout(t *testing.T) {
	tr := NewAuthThrottler()
	tr.maxFailures = 3
	tr.window = time.Minute
	tr.lockout = time.Minute

	ip := "10.0.0.1"
	if ok, _ := tr.Allowed(ip); !ok {
		t.Fatal("fresh IP should be allowed")
	}

	// Two failures: still allowed.
	tr.RecordFailure(ip)
	tr.RecordFailure(ip)
	if ok, _ := tr.Allowed(ip); !ok {
		t.Fatal("IP should still be allowed below the threshold")
	}

	// Third failure trips the lockout.
	if locked := tr.RecordFailure(ip); !locked {
		t.Fatal("third failure should trip the lockout")
	}
	ok, remaining := tr.Allowed(ip)
	if ok {
		t.Fatal("IP should be locked out after reaching the threshold")
	}
	if remaining <= 0 || remaining > time.Minute {
		t.Errorf("unexpected lockout remaining: %v", remaining)
	}
}

func TestAuthThrottlerSuccessResets(t *testing.T) {
	tr := NewAuthThrottler()
	tr.maxFailures = 3
	ip := "10.0.0.2"

	tr.RecordFailure(ip)
	tr.RecordFailure(ip)
	tr.RecordSuccess(ip) // clears state

	// After reset it should take the full threshold again to lock.
	if locked := tr.RecordFailure(ip); locked {
		t.Error("failure count should have reset after success")
	}
}

func TestAuthThrottlerIsolation(t *testing.T) {
	tr := NewAuthThrottler()
	tr.maxFailures = 2

	tr.RecordFailure("1.1.1.1")
	tr.RecordFailure("1.1.1.1") // locks 1.1.1.1

	if ok, _ := tr.Allowed("1.1.1.1"); ok {
		t.Error("1.1.1.1 should be locked")
	}
	if ok, _ := tr.Allowed("2.2.2.2"); !ok {
		t.Error("a different IP should be unaffected")
	}
}
