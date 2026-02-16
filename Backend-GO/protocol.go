package main

import (
	"fmt"
	"strconv"
	"strings"
)

const (
	ProtocolVersion    = "1.1"
	MinSupportedVersion = "1.0"
)

// Error codes for NACK responses
const (
	ErrCodeNone       = 0
	ErrCodeBadFormat  = 1
	ErrCodeInvalidCmd = 2
	ErrCodeRateLimit  = 3
	ErrCodeValidation = 4
	ErrCodeInternal   = 5
	ErrCodeAuthFailed = 6
)

// Message represents a parsed protocol message
type Message struct {
	SeqID   int
	Command string
	Payload string
	Raw     string
}

// ParseMessage parses a protocol message
// Format: <seq_id>|<command>:<payload>
// Legacy format (no seq_id): <command>:<payload>
func ParseMessage(data string) (*Message, error) {
	// Only trim newlines and carriage returns, preserve spaces in payload
	data = strings.TrimRight(data, "\r\n")
	if strings.TrimSpace(data) == "" {
		return nil, fmt.Errorf("empty message")
	}

	msg := &Message{
		Raw:   data,
		SeqID: -1, // -1 indicates no sequence ID (legacy format)
	}

	// Check for new protocol format with sequence ID
	if idx := strings.Index(data, "|"); idx > 0 {
		seqStr := data[:idx]
		seqID, err := strconv.Atoi(seqStr)
		if err == nil {
			msg.SeqID = seqID
			data = data[idx+1:]
		}
	}

	// Parse command:payload
	parts := strings.SplitN(data, ":", 2)
	msg.Command = parts[0]
	if len(parts) > 1 {
		msg.Payload = parts[1]
	}

	return msg, nil
}

// FormatACK creates an ACK response
func FormatACK(seqID int) string {
	if seqID < 0 {
		return "ACK\n"
	}
	return fmt.Sprintf("%d|ACK\n", seqID)
}

// FormatNACK creates a NACK response with error code
func FormatNACK(seqID int, errCode int) string {
	if seqID < 0 {
		return fmt.Sprintf("NACK:%d\n", errCode)
	}
	return fmt.Sprintf("%d|NACK:%d\n", seqID, errCode)
}

// FormatResponse creates a response with data
func FormatResponse(seqID int, response string) string {
	if seqID < 0 {
		return response + "\n"
	}
	return fmt.Sprintf("%d|%s\n", seqID, response)
}

// FormatVersionResponse creates a VERSION response
func FormatVersionResponse(clientVersion string) string {
	if clientVersion == ProtocolVersion {
		return fmt.Sprintf("VERSION:%s:OK\n", ProtocolVersion)
	}

	// Check if versions are compatible (same major version)
	clientParts := strings.Split(clientVersion, ".")
	serverParts := strings.Split(ProtocolVersion, ".")

	if len(clientParts) > 0 && len(serverParts) > 0 && clientParts[0] == serverParts[0] {
		return fmt.Sprintf("VERSION:%s:COMPATIBLE\n", ProtocolVersion)
	}

	// Check if client version is at least the minimum supported
	minParts := strings.Split(MinSupportedVersion, ".")
	if len(clientParts) > 0 && len(minParts) > 0 && clientParts[0] >= minParts[0] {
		return fmt.Sprintf("VERSION:%s:COMPATIBLE\n", ProtocolVersion)
	}

	return fmt.Sprintf("VERSION:%s:INCOMPATIBLE\n", ProtocolVersion)
}

// FormatPong creates a PONG response
func FormatPong() string {
	return "PONG\n"
}

// Response types
type ResponseType int

const (
	ResponseACK ResponseType = iota
	ResponseNACK
	ResponseData
	ResponsePong
	ResponseVersion
	ResponseAuth
)

// Response represents a server response
type Response struct {
	Type    ResponseType
	SeqID   int
	ErrCode int
	Data    string
}

// String formats the response as a string
func (r *Response) String() string {
	switch r.Type {
	case ResponseACK:
		return FormatACK(r.SeqID)
	case ResponseNACK:
		return FormatNACK(r.SeqID, r.ErrCode)
	case ResponsePong:
		return FormatPong()
	case ResponseData:
		return FormatResponse(r.SeqID, r.Data)
	default:
		return r.Data + "\n"
	}
}

// NewACKResponse creates an ACK response
func NewACKResponse(seqID int) *Response {
	return &Response{Type: ResponseACK, SeqID: seqID}
}

// NewNACKResponse creates a NACK response
func NewNACKResponse(seqID int, errCode int) *Response {
	return &Response{Type: ResponseNACK, SeqID: seqID, ErrCode: errCode}
}

// NewPongResponse creates a PONG response
func NewPongResponse() *Response {
	return &Response{Type: ResponsePong}
}

// NewDataResponse creates a data response
func NewDataResponse(seqID int, data string) *Response {
	return &Response{Type: ResponseData, SeqID: seqID, Data: data}
}

// MessageHandler is a function that handles a parsed message
type MessageHandler func(msg *Message) *Response

// HandlerRegistry maps commands to their handlers
type HandlerRegistry struct {
	handlers map[string]MessageHandler
}

// NewHandlerRegistry creates a new handler registry
func NewHandlerRegistry() *HandlerRegistry {
	return &HandlerRegistry{
		handlers: make(map[string]MessageHandler),
	}
}

// Register registers a handler for a command
func (hr *HandlerRegistry) Register(command string, handler MessageHandler) {
	hr.handlers[command] = handler
}

// Handle processes a message using the registered handler
func (hr *HandlerRegistry) Handle(msg *Message) *Response {
	handler, exists := hr.handlers[msg.Command]
	if !exists {
		return NewNACKResponse(msg.SeqID, ErrCodeInvalidCmd)
	}
	return handler(msg)
}

// HasHandler checks if a handler exists for a command
func (hr *HandlerRegistry) HasHandler(command string) bool {
	_, exists := hr.handlers[command]
	return exists
}
