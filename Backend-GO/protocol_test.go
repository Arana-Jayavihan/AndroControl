package main

import "testing"

func TestParseMessage(t *testing.T) {
	t.Run("with seq id", func(t *testing.T) {
		msg, err := ParseMessage("5|M:10,20")
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if msg.SeqID != 5 || msg.Command != "M" || msg.Payload != "10,20" {
			t.Errorf("got SeqID=%d Command=%q Payload=%q", msg.SeqID, msg.Command, msg.Payload)
		}
	})

	t.Run("legacy no seq id", func(t *testing.T) {
		msg, err := ParseMessage("C:left")
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if msg.SeqID != -1 || msg.Command != "C" || msg.Payload != "left" {
			t.Errorf("got SeqID=%d Command=%q Payload=%q", msg.SeqID, msg.Command, msg.Payload)
		}
	})

	t.Run("command only", func(t *testing.T) {
		msg, err := ParseMessage("PING")
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if msg.Command != "PING" || msg.Payload != "" {
			t.Errorf("got Command=%q Payload=%q", msg.Command, msg.Payload)
		}
	})

	t.Run("payload preserves spaces", func(t *testing.T) {
		msg, err := ParseMessage("7|T:hello world")
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if msg.Payload != "hello world" {
			t.Errorf("payload spaces not preserved: %q", msg.Payload)
		}
	})

	t.Run("empty rejected", func(t *testing.T) {
		if _, err := ParseMessage("   "); err == nil {
			t.Error("expected error for empty message")
		}
	})
}

func TestFormatACKNACK(t *testing.T) {
	if got := FormatACK(5); got != "5|ACK\n" {
		t.Errorf("FormatACK(5)=%q", got)
	}
	if got := FormatACK(-1); got != "ACK\n" {
		t.Errorf("FormatACK(-1)=%q", got)
	}
	if got := FormatNACK(5, ErrCodeValidation); got != "5|NACK:4\n" {
		t.Errorf("FormatNACK(5,4)=%q", got)
	}
	if got := FormatNACK(-1, ErrCodeValidation); got != "NACK:4\n" {
		t.Errorf("FormatNACK(-1,4)=%q", got)
	}
}

func TestFormatVersionResponse(t *testing.T) {
	if got := FormatVersionResponse(ProtocolVersion); got != "VERSION:"+ProtocolVersion+":OK\n" {
		t.Errorf("same version=%q", got)
	}
	// Same major version, different minor → compatible.
	if got := FormatVersionResponse("2.9"); got != "VERSION:"+ProtocolVersion+":COMPATIBLE\n" {
		t.Errorf("same major=%q", got)
	}
}

func TestResponseString(t *testing.T) {
	if got := NewACKResponse(3).String(); got != "3|ACK\n" {
		t.Errorf("ACK response=%q", got)
	}
	if got := NewNACKResponse(3, ErrCodeInvalidCmd).String(); got != "3|NACK:2\n" {
		t.Errorf("NACK response=%q", got)
	}
	if got := NewPongResponse().String(); got != "PONG\n" {
		t.Errorf("PONG response=%q", got)
	}
}
