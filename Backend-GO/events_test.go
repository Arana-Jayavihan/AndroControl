package main

import (
	"testing"
	"time"
)

func TestEventHookEnv(t *testing.T) {
	env := eventHookEnv("connect", "id123", "Pixel 8", "192.168.1.20", 0)

	want := map[string]string{
		"ANDROCONTROL_EVENT":       "connect",
		"ANDROCONTROL_DEVICE_ID":   "id123",
		"ANDROCONTROL_DEVICE_NAME": "Pixel 8",
		"ANDROCONTROL_IP":          "192.168.1.20",
	}
	got := envMap(env)
	for k, v := range want {
		if got[k] != v {
			t.Errorf("%s = %q, want %q", k, got[k], v)
		}
	}
	if _, ok := got["ANDROCONTROL_DURATION"]; ok {
		t.Error("connect event should not set ANDROCONTROL_DURATION")
	}

	// A disconnect with a positive duration includes ANDROCONTROL_DURATION (seconds).
	env = eventHookEnv("disconnect", "id123", "Pixel 8", "192.168.1.20", 90*time.Second)
	if got := envMap(env)["ANDROCONTROL_DURATION"]; got != "90" {
		t.Errorf("ANDROCONTROL_DURATION = %q, want %q", got, "90")
	}
}

// runEventHook must be a safe no-op when no hook command is configured.
func TestRunEventHookDisabled(t *testing.T) {
	eventHookCmd = ""
	runEventHook("connect", "id", "name", "ip", 0) // must not panic or block
}

func envMap(env []string) map[string]string {
	m := make(map[string]string, len(env))
	for _, e := range env {
		for i := 0; i < len(e); i++ {
			if e[i] == '=' {
				m[e[:i]] = e[i+1:]
				break
			}
		}
	}
	return m
}
