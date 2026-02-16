package main

import (
	"bufio"
	"crypto/tls"
	"fmt"
	"log"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/bendahl/uinput"
)

const (
	HOST = "0.0.0.0"
	PORT = 5050

	// Timeouts
	AuthTimeoutDuration = 5 * time.Second
	IdleTimeout         = 60 * time.Second
	HeartbeatCheck      = 30 * time.Second
)

var (
	keyboard   uinput.Keyboard
	mouse      uinput.Mouse
	keyboardMu sync.Mutex
	mouseMu    sync.Mutex

	// Global managers
	connManager    *ConnectionManager
	authManager    *AuthManager
	rateLimiters   *ClientRateLimiters
	tlsConfig      *TLSConfig
)

func init() {
	var err error
	keyboard, err = uinput.CreateKeyboard("/dev/uinput", []byte("virtual-kbd"))
	if err != nil {
		log.Fatalf("Failed to create keyboard: %v", err)
	}

	mouse, err = uinput.CreateMouse("/dev/uinput", []byte("virtual-mouse"))
	if err != nil {
		log.Fatalf("Failed to create mouse: %v", err)
	}

	// Initialize managers
	connManager = NewConnectionManager()
	authManager = NewAuthManager()
	rateLimiters = NewClientRateLimiters()
	tlsConfig = NewTLSConfig()
}

func asciiToUinput(ascii int) (int, error) {
	switch ascii {
	case 97:
		return uinput.KeyA, nil
	case 98:
		return uinput.KeyB, nil
	case 99:
		return uinput.KeyC, nil
	case 100:
		return uinput.KeyD, nil
	case 101:
		return uinput.KeyE, nil
	case 102:
		return uinput.KeyF, nil
	case 103:
		return uinput.KeyG, nil
	case 104:
		return uinput.KeyH, nil
	case 105:
		return uinput.KeyI, nil
	case 106:
		return uinput.KeyJ, nil
	case 107:
		return uinput.KeyK, nil
	case 108:
		return uinput.KeyL, nil
	case 109:
		return uinput.KeyM, nil
	case 110:
		return uinput.KeyN, nil
	case 111:
		return uinput.KeyO, nil
	case 112:
		return uinput.KeyP, nil
	case 113:
		return uinput.KeyQ, nil
	case 114:
		return uinput.KeyR, nil
	case 115:
		return uinput.KeyS, nil
	case 116:
		return uinput.KeyT, nil
	case 117:
		return uinput.KeyU, nil
	case 118:
		return uinput.KeyV, nil
	case 119:
		return uinput.KeyW, nil
	case 120:
		return uinput.KeyX, nil
	case 121:
		return uinput.KeyY, nil
	case 122:
		return uinput.KeyZ, nil

	case 65:
		return uinput.KeyA, nil
	case 66:
		return uinput.KeyB, nil
	case 67:
		return uinput.KeyC, nil
	case 68:
		return uinput.KeyD, nil
	case 69:
		return uinput.KeyE, nil
	case 70:
		return uinput.KeyF, nil
	case 71:
		return uinput.KeyG, nil
	case 72:
		return uinput.KeyH, nil
	case 73:
		return uinput.KeyI, nil
	case 74:
		return uinput.KeyJ, nil
	case 75:
		return uinput.KeyK, nil
	case 76:
		return uinput.KeyL, nil
	case 77:
		return uinput.KeyM, nil
	case 78:
		return uinput.KeyN, nil
	case 79:
		return uinput.KeyO, nil
	case 80:
		return uinput.KeyP, nil
	case 81:
		return uinput.KeyQ, nil
	case 82:
		return uinput.KeyR, nil
	case 83:
		return uinput.KeyS, nil
	case 84:
		return uinput.KeyT, nil
	case 85:
		return uinput.KeyU, nil
	case 86:
		return uinput.KeyV, nil
	case 87:
		return uinput.KeyW, nil
	case 88:
		return uinput.KeyX, nil
	case 89:
		return uinput.KeyY, nil
	case 90:
		return uinput.KeyZ, nil

	case 48:
		return uinput.Key0, nil
	case 49:
		return uinput.Key1, nil
	case 50:
		return uinput.Key2, nil
	case 51:
		return uinput.Key3, nil
	case 52:
		return uinput.Key4, nil
	case 53:
		return uinput.Key5, nil
	case 54:
		return uinput.Key6, nil
	case 55:
		return uinput.Key7, nil
	case 56:
		return uinput.Key8, nil
	case 57:
		return uinput.Key9, nil

	case 32:
		return uinput.KeySpace, nil
	case 10:
		return uinput.KeyEnter, nil

	default:
		return 0, fmt.Errorf("unsupported ASCII code: %d", ascii)
	}
}

// Thread-safe mouse operations
func safeMouseMove(x, y int32) {
	mouseMu.Lock()
	defer mouseMu.Unlock()
	mouse.Move(x, y)
}

func safeMouseClick(button string) {
	mouseMu.Lock()
	defer mouseMu.Unlock()
	switch button {
	case "left":
		mouse.LeftClick()
	case "right":
		mouse.RightClick()
	case "middle":
		mouse.MiddleClick()
	}
}

func safeMouseWheel(amount int32) {
	mouseMu.Lock()
	defer mouseMu.Unlock()
	mouse.Wheel(false, amount)
}

// Thread-safe keyboard operations
func safeKeyPress(key int) {
	keyboardMu.Lock()
	defer keyboardMu.Unlock()
	keyboard.KeyPress(key)
}

func safeKeyDown(key int) {
	keyboardMu.Lock()
	defer keyboardMu.Unlock()
	keyboard.KeyDown(key)
}

func safeKeyUp(key int) {
	keyboardMu.Lock()
	defer keyboardMu.Unlock()
	keyboard.KeyUp(key)
}

func safeTypeChar(r rune) {
	keyboardMu.Lock()
	defer keyboardMu.Unlock()

	key, err := asciiToUinput(int(r))
	if err != nil {
		log.Printf("Unsupported ASCII %d: %v", r, err)
		return
	}

	if int(r) >= 65 && int(r) <= 90 {
		keyboard.KeyDown(uinput.KeyLeftshift)
		keyboard.KeyPress(key)
		keyboard.KeyUp(uinput.KeyLeftshift)
	} else {
		keyboard.KeyPress(key)
	}
}

// sendResponse sends a response to the client
func sendResponse(conn net.Conn, response string) error {
	_, err := conn.Write([]byte(response))
	return err
}

// authenticateClient handles the authentication handshake
func authenticateClient(conn net.Conn, reader *bufio.Reader) bool {
	// Set auth timeout
	conn.SetReadDeadline(time.Now().Add(AuthTimeoutDuration))

	line, err := reader.ReadString('\n')
	if err != nil {
		log.Printf("Auth timeout or read error from %s: %v", conn.RemoteAddr(), err)
		sendResponse(conn, AuthTimeout.String()+"\n")
		return false
	}

	token, err := ParseAuthMessage(strings.TrimSpace(line))
	if err != nil {
		log.Printf("Invalid auth format from %s: %v", conn.RemoteAddr(), err)
		sendResponse(conn, AuthInvalidFormat.String()+"\n")
		return false
	}

	if !authManager.Validate(token) {
		log.Printf("Auth failed from %s", conn.RemoteAddr())
		sendResponse(conn, AuthFailed.String()+"\n")
		return false
	}

	log.Printf("Auth successful from %s", conn.RemoteAddr())
	sendResponse(conn, AuthSuccess.String()+"\n")

	// Clear read deadline
	conn.SetReadDeadline(time.Time{})
	return true
}

// handleCommand processes a single command and returns a response
func handleCommand(msg *Message, clientID string) *Response {
	// Check rate limit
	limiter := rateLimiters.GetLimiter(clientID)
	if !limiter.Allow() {
		return NewNACKResponse(msg.SeqID, ErrCodeRateLimit)
	}

	// Validate payload
	if err := ValidatePayload(msg.Payload); err != nil {
		return NewNACKResponse(msg.SeqID, ErrCodeValidation)
	}

	switch msg.Command {
	case "M":
		coords := strings.Split(strings.Split(msg.Payload, "\\")[0], ",")
		if len(coords) == 2 {
			x, errX := strconv.Atoi(coords[0])
			y, errY := strconv.Atoi(coords[1])
			if errX != nil || errY != nil {
				return NewNACKResponse(msg.SeqID, ErrCodeValidation)
			}
			if err := ValidateMovement(x, y); err != nil {
				return NewNACKResponse(msg.SeqID, ErrCodeValidation)
			}
			safeMouseMove(int32(x), int32(y))
		} else {
			return NewNACKResponse(msg.SeqID, ErrCodeBadFormat)
		}

	case "C":
		if err := ValidateMouseButton(msg.Payload); err != nil {
			return NewNACKResponse(msg.SeqID, ErrCodeValidation)
		}
		safeMouseClick(msg.Payload)

	case "S":
		amount, err := strconv.Atoi(msg.Payload)
		if err != nil {
			return NewNACKResponse(msg.SeqID, ErrCodeValidation)
		}
		if err := ValidateScroll(amount); err != nil {
			return NewNACKResponse(msg.SeqID, ErrCodeValidation)
		}
		safeMouseWheel(int32(-amount))

	case "T":
		text, err := SanitizeText(msg.Payload)
		if err != nil {
			return NewNACKResponse(msg.SeqID, ErrCodeValidation)
		}
		for _, r := range text {
			safeTypeChar(r)
		}

	case "TB":
		safeKeyPress(uinput.KeyBackspace)

	case "SPACE":
		safeKeyPress(uinput.KeySpace)

	case "ENTER":
		safeKeyPress(uinput.KeyEnter)

	case "PING":
		return NewPongResponse()

	case "VERSION":
		return NewDataResponse(msg.SeqID, FormatVersionResponse(msg.Payload))

	default:
		log.Printf("Unknown command: %s", msg.Command)
		return NewNACKResponse(msg.SeqID, ErrCodeInvalidCmd)
	}

	return NewACKResponse(msg.SeqID)
}

func handleClient(conn net.Conn) {
	clientAddr := conn.RemoteAddr()
	clientID := clientAddr.String()

	defer func() {
		conn.Close()
		connManager.Release(clientAddr)
		rateLimiters.RemoveLimiter(clientID)
		log.Printf("Connection closed: %s", clientAddr)
	}()

	reader := bufio.NewReader(conn)

	// Authentication handshake
	if !authenticateClient(conn, reader) {
		return
	}

	// Main command loop
	for {
		// Set idle timeout
		conn.SetReadDeadline(time.Now().Add(IdleTimeout))

		line, err := reader.ReadString('\n')
		if err != nil {
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				log.Printf("Idle timeout for %s", clientAddr)
				sendResponse(conn, "TIMEOUT\n")
			} else {
				log.Printf("Read error from %s: %v", clientAddr, err)
			}
			return
		}

		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}

		// Handle DISCONNECT gracefully
		if line == "DISCONNECT" {
			log.Printf("Client %s disconnected gracefully", clientAddr)
			return
		}

		// Parse message
		msg, err := ParseMessage(line)
		if err != nil {
			sendResponse(conn, FormatNACK(-1, ErrCodeBadFormat))
			continue
		}

		// Process command
		response := handleCommand(msg, clientID)
		if response != nil {
			sendResponse(conn, response.String())
		}
	}
}

func main() {
	defer keyboard.Close()
	defer mouse.Close()

	// Initialize TLS
	if err := tlsConfig.EnsureCertificates(); err != nil {
		log.Fatalf("Failed to setup TLS certificates: %v", err)
	}

	tlsCfg, err := tlsConfig.LoadTLSConfig()
	if err != nil {
		log.Fatalf("Failed to load TLS config: %v", err)
	}

	// Initialize authentication
	if err := authManager.Initialize(); err != nil {
		log.Fatalf("Failed to initialize authentication: %v", err)
	}

	// Create TLS listener
	listener, err := tls.Listen("tcp", fmt.Sprintf("%s:%d", HOST, PORT), tlsCfg)
	if err != nil {
		log.Fatalf("Failed to start TLS server: %v", err)
	}
	defer listener.Close()

	log.Println("========================================")
	log.Printf("AndroControl Server v%s", ProtocolVersion)
	log.Printf("Listening on %s:%d (TLS)", HOST, PORT)
	log.Println("========================================")

	// Print certificate and token info
	tlsConfig.PrintCertificateInfo()
	authManager.PrintToken()

	log.Println("Waiting for connections...")

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Printf("Error accepting connection: %v", err)
			continue
		}

		// Check connection limits
		if err := connManager.TryAccept(conn.RemoteAddr()); err != nil {
			log.Printf("Connection rejected from %s: %v", conn.RemoteAddr(), err)
			conn.Write([]byte("ERROR:TOO_MANY_CONNECTIONS\n"))
			conn.Close()
			continue
		}

		log.Printf("Connection accepted from %s", conn.RemoteAddr())
		go handleClient(conn)
	}
}
