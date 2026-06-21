package main

import (
	"log"
	"strings"
)

// LogLevel controls how verbose the server is.
type LogLevel int

const (
	LevelDebug LogLevel = iota
	LevelInfo
	LevelWarn
	LevelError
)

var currentLevel = LevelInfo

// SetLogLevel sets the global log level from a name (debug/info/warn/error).
func SetLogLevel(name string) {
	switch strings.ToLower(strings.TrimSpace(name)) {
	case "debug":
		currentLevel = LevelDebug
	case "warn", "warning":
		currentLevel = LevelWarn
	case "error":
		currentLevel = LevelError
	case "info", "":
		currentLevel = LevelInfo
	default:
		currentLevel = LevelInfo
	}
}

func logDebug(format string, a ...interface{}) {
	if currentLevel <= LevelDebug {
		log.Printf("[DEBUG] "+format, a...)
	}
}

func logInfo(format string, a ...interface{}) {
	if currentLevel <= LevelInfo {
		log.Printf("[INFO] "+format, a...)
	}
}

func logWarn(format string, a ...interface{}) {
	if currentLevel <= LevelWarn {
		log.Printf("[WARN] "+format, a...)
	}
}

func logError(format string, a ...interface{}) {
	if currentLevel <= LevelError {
		log.Printf("[ERROR] "+format, a...)
	}
}

// logAudit records security-relevant events (auth, pairing, lockouts). These are
// always emitted regardless of the configured level so the audit trail is intact.
func logAudit(format string, a ...interface{}) {
	log.Printf("[AUDIT] "+format, a...)
}
