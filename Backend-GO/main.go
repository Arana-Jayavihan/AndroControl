package main

import (
	"bufio"
	"crypto/tls"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
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
	sessionManager *SessionManager
	rateLimiters   *ClientRateLimiters
	tlsConfig      *TLSConfig
	deviceManager  *DeviceManager
)

func init() {
	// Initialize managers. Note: the uinput virtual devices are created later in
	// main() (only when actually starting the server) so that admin commands such
	// as -list-devices / -revoke can run on machines without /dev/uinput access.
	connManager = NewConnectionManager()
	authManager = NewAuthManager()
	sessionManager = NewSessionManager()
	rateLimiters = NewClientRateLimiters()
	tlsConfig = NewTLSConfig()
	deviceManager = NewDeviceManager()
}

// initInputDevices creates the virtual keyboard and mouse via uinput.
// Requires access to /dev/uinput (uinput kernel module + appropriate permissions).
func initInputDevices() error {
	var err error
	keyboard, err = uinput.CreateKeyboard("/dev/uinput", []byte("virtual-kbd"))
	if err != nil {
		return fmt.Errorf("failed to create virtual keyboard (is the uinput module loaded and accessible?): %w", err)
	}

	mouse, err = uinput.CreateMouse("/dev/uinput", []byte("virtual-mouse"))
	if err != nil {
		keyboard.Close()
		return fmt.Errorf("failed to create virtual mouse (is the uinput module loaded and accessible?): %w", err)
	}
	return nil
}

// CharMapping holds the key and whether shift is needed
type CharMapping struct {
	Key       int
	NeedShift bool
}

// charToKey maps characters to their uinput key codes (US keyboard layout)
var charToKey = map[rune]CharMapping{
	// Lowercase letters
	'a': {uinput.KeyA, false}, 'b': {uinput.KeyB, false}, 'c': {uinput.KeyC, false},
	'd': {uinput.KeyD, false}, 'e': {uinput.KeyE, false}, 'f': {uinput.KeyF, false},
	'g': {uinput.KeyG, false}, 'h': {uinput.KeyH, false}, 'i': {uinput.KeyI, false},
	'j': {uinput.KeyJ, false}, 'k': {uinput.KeyK, false}, 'l': {uinput.KeyL, false},
	'm': {uinput.KeyM, false}, 'n': {uinput.KeyN, false}, 'o': {uinput.KeyO, false},
	'p': {uinput.KeyP, false}, 'q': {uinput.KeyQ, false}, 'r': {uinput.KeyR, false},
	's': {uinput.KeyS, false}, 't': {uinput.KeyT, false}, 'u': {uinput.KeyU, false},
	'v': {uinput.KeyV, false}, 'w': {uinput.KeyW, false}, 'x': {uinput.KeyX, false},
	'y': {uinput.KeyY, false}, 'z': {uinput.KeyZ, false},

	// Uppercase letters (need shift)
	'A': {uinput.KeyA, true}, 'B': {uinput.KeyB, true}, 'C': {uinput.KeyC, true},
	'D': {uinput.KeyD, true}, 'E': {uinput.KeyE, true}, 'F': {uinput.KeyF, true},
	'G': {uinput.KeyG, true}, 'H': {uinput.KeyH, true}, 'I': {uinput.KeyI, true},
	'J': {uinput.KeyJ, true}, 'K': {uinput.KeyK, true}, 'L': {uinput.KeyL, true},
	'M': {uinput.KeyM, true}, 'N': {uinput.KeyN, true}, 'O': {uinput.KeyO, true},
	'P': {uinput.KeyP, true}, 'Q': {uinput.KeyQ, true}, 'R': {uinput.KeyR, true},
	'S': {uinput.KeyS, true}, 'T': {uinput.KeyT, true}, 'U': {uinput.KeyU, true},
	'V': {uinput.KeyV, true}, 'W': {uinput.KeyW, true}, 'X': {uinput.KeyX, true},
	'Y': {uinput.KeyY, true}, 'Z': {uinput.KeyZ, true},

	// Numbers
	'0': {uinput.Key0, false}, '1': {uinput.Key1, false}, '2': {uinput.Key2, false},
	'3': {uinput.Key3, false}, '4': {uinput.Key4, false}, '5': {uinput.Key5, false},
	'6': {uinput.Key6, false}, '7': {uinput.Key7, false}, '8': {uinput.Key8, false},
	'9': {uinput.Key9, false},

	// Shift + Numbers (symbols)
	'!': {uinput.Key1, true}, '@': {uinput.Key2, true}, '#': {uinput.Key3, true},
	'$': {uinput.Key4, true}, '%': {uinput.Key5, true}, '^': {uinput.Key6, true},
	'&': {uinput.Key7, true}, '*': {uinput.Key8, true}, '(': {uinput.Key9, true},
	')': {uinput.Key0, true},

	// Special characters
	' ':  {uinput.KeySpace, false},
	'\n': {uinput.KeyEnter, false},
	'\t': {uinput.KeyTab, false},

	// Punctuation (no shift)
	'`':  {uinput.KeyGrave, false},
	'-':  {uinput.KeyMinus, false},
	'=':  {uinput.KeyEqual, false},
	'[':  {uinput.KeyLeftbrace, false},
	']':  {uinput.KeyRightbrace, false},
	'\\': {uinput.KeyBackslash, false},
	';':  {uinput.KeySemicolon, false},
	'\'': {uinput.KeyApostrophe, false},
	',':  {uinput.KeyComma, false},
	'.':  {uinput.KeyDot, false},
	'/':  {uinput.KeySlash, false},

	// Punctuation (with shift)
	'~': {uinput.KeyGrave, true},
	'_': {uinput.KeyMinus, true},
	'+': {uinput.KeyEqual, true},
	'{': {uinput.KeyLeftbrace, true},
	'}': {uinput.KeyRightbrace, true},
	'|': {uinput.KeyBackslash, true},
	':': {uinput.KeySemicolon, true},
	'"': {uinput.KeyApostrophe, true},
	'<': {uinput.KeyComma, true},
	'>': {uinput.KeyDot, true},
	'?': {uinput.KeySlash, true},
}

func asciiToUinput(ascii int) (int, error) {
	r := rune(ascii)
	if mapping, ok := charToKey[r]; ok {
		return mapping.Key, nil
	}
	return 0, fmt.Errorf("unsupported character: %c (%d)", r, ascii)
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

func safeMouseButtonDown(button string) {
	mouseMu.Lock()
	defer mouseMu.Unlock()
	switch button {
	case "left":
		mouse.LeftPress()
	case "right":
		mouse.RightPress()
	case "middle":
		mouse.MiddlePress()
	}
}

func safeMouseButtonUp(button string) {
	mouseMu.Lock()
	defer mouseMu.Unlock()
	switch button {
	case "left":
		mouse.LeftRelease()
	case "right":
		mouse.RightRelease()
	case "middle":
		mouse.MiddleRelease()
	}
}

func safeDoubleClick(button string) {
	mouseMu.Lock()
	defer mouseMu.Unlock()
	switch button {
	case "left":
		mouse.LeftClick()
		time.Sleep(50 * time.Millisecond)
		mouse.LeftClick()
	case "right":
		mouse.RightClick()
		time.Sleep(50 * time.Millisecond)
		mouse.RightClick()
	case "middle":
		mouse.MiddleClick()
		time.Sleep(50 * time.Millisecond)
		mouse.MiddleClick()
	}
}

// keyNameToUinput maps key names to uinput key codes
func keyNameToUinput(keyName string) (int, error) {
	keyMap := map[string]int{
		// Arrow keys
		"UP":    uinput.KeyUp,
		"DOWN":  uinput.KeyDown,
		"LEFT":  uinput.KeyLeft,
		"RIGHT": uinput.KeyRight,

		// Function keys
		"F1":  uinput.KeyF1,
		"F2":  uinput.KeyF2,
		"F3":  uinput.KeyF3,
		"F4":  uinput.KeyF4,
		"F5":  uinput.KeyF5,
		"F6":  uinput.KeyF6,
		"F7":  uinput.KeyF7,
		"F8":  uinput.KeyF8,
		"F9":  uinput.KeyF9,
		"F10": uinput.KeyF10,
		"F11": uinput.KeyF11,
		"F12": uinput.KeyF12,

		// Modifier keys
		"CTRL":       uinput.KeyLeftctrl,
		"LCTRL":      uinput.KeyLeftctrl,
		"RCTRL":      uinput.KeyRightctrl,
		"ALT":        uinput.KeyLeftalt,
		"LALT":       uinput.KeyLeftalt,
		"RALT":       uinput.KeyRightalt,
		"SHIFT":      uinput.KeyLeftshift,
		"LSHIFT":     uinput.KeyLeftshift,
		"RSHIFT":     uinput.KeyRightshift,
		"SUPER":      uinput.KeyLeftmeta,
		"WIN":        uinput.KeyLeftmeta,
		"META":       uinput.KeyLeftmeta,

		// Special keys
		"TAB":        uinput.KeyTab,
		"ESC":        uinput.KeyEsc,
		"ESCAPE":     uinput.KeyEsc,
		"HOME":       uinput.KeyHome,
		"END":        uinput.KeyEnd,
		"PAGEUP":     uinput.KeyPageup,
		"PAGEDOWN":   uinput.KeyPagedown,
		"DELETE":     uinput.KeyDelete,
		"DEL":        uinput.KeyDelete,
		"INSERT":     uinput.KeyInsert,
		"INS":        uinput.KeyInsert,
		"BACKSPACE":  uinput.KeyBackspace,
		"ENTER":      uinput.KeyEnter,
		"RETURN":     uinput.KeyEnter,
		"SPACE":      uinput.KeySpace,
		"CAPSLOCK":   uinput.KeyCapslock,
		"NUMLOCK":    uinput.KeyNumlock,
		"SCROLLLOCK": uinput.KeyScrolllock,
		"PRINTSCREEN": uinput.KeySysrq,
		"PAUSE":      uinput.KeyPause,
		"MENU":       uinput.KeyCompose,

		// Single letter keys (for combos)
		"A": uinput.KeyA, "B": uinput.KeyB, "C": uinput.KeyC,
		"D": uinput.KeyD, "E": uinput.KeyE, "F": uinput.KeyF,
		"G": uinput.KeyG, "H": uinput.KeyH, "I": uinput.KeyI,
		"J": uinput.KeyJ, "K": uinput.KeyK, "L": uinput.KeyL,
		"M": uinput.KeyM, "N": uinput.KeyN, "O": uinput.KeyO,
		"P": uinput.KeyP, "Q": uinput.KeyQ, "R": uinput.KeyR,
		"S": uinput.KeyS, "T": uinput.KeyT, "U": uinput.KeyU,
		"V": uinput.KeyV, "W": uinput.KeyW, "X": uinput.KeyX,
		"Y": uinput.KeyY, "Z": uinput.KeyZ,

		// Number keys
		"0": uinput.Key0, "1": uinput.Key1, "2": uinput.Key2,
		"3": uinput.Key3, "4": uinput.Key4, "5": uinput.Key5,
		"6": uinput.Key6, "7": uinput.Key7, "8": uinput.Key8,
		"9": uinput.Key9,
	}

	if key, ok := keyMap[strings.ToUpper(keyName)]; ok {
		return key, nil
	}
	return 0, fmt.Errorf("unknown key: %s", keyName)
}

// executeKeyCombo executes a key combination like "CTRL+C" or "CTRL+SHIFT+S"
func executeKeyCombo(combo string) error {
	parts := strings.Split(strings.ToUpper(combo), "+")
	if len(parts) == 0 {
		return fmt.Errorf("empty combo")
	}

	// Get all key codes
	keys := make([]int, len(parts))
	for i, part := range parts {
		key, err := keyNameToUinput(strings.TrimSpace(part))
		if err != nil {
			return err
		}
		keys[i] = key
	}

	keyboardMu.Lock()
	defer keyboardMu.Unlock()

	// Press all modifier keys (all but last)
	for i := 0; i < len(keys)-1; i++ {
		keyboard.KeyDown(keys[i])
	}

	// Press and release the final key
	keyboard.KeyPress(keys[len(keys)-1])

	// Release modifier keys in reverse order
	for i := len(keys) - 2; i >= 0; i-- {
		keyboard.KeyUp(keys[i])
	}

	return nil
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

	mapping, ok := charToKey[r]
	if !ok {
		log.Printf("Unsupported character: %c (%d)", r, r)
		return
	}

	if mapping.NeedShift {
		keyboard.KeyDown(uinput.KeyLeftshift)
		keyboard.KeyPress(mapping.Key)
		keyboard.KeyUp(uinput.KeyLeftshift)
	} else {
		keyboard.KeyPress(mapping.Key)
	}
}

// sendResponse sends a response to the client
func sendResponse(conn net.Conn, response string) error {
	_, err := conn.Write([]byte(response))
	return err
}

// authenticateClient handles the authentication handshake.
// Supports two flows over a single connection:
//
//	PAIR:<enrollment_token>:<device_name>  -> registers a new per-device token
//	AUTH:<device_token>                    -> authenticates an already-paired device
//
// On success it creates a session and returns its token; on failure it returns "".
func authenticateClient(conn net.Conn, reader *bufio.Reader) string {
	clientID := conn.RemoteAddr().String()
	clientIP := extractIP(conn.RemoteAddr())

	// Set auth timeout
	conn.SetReadDeadline(time.Now().Add(AuthTimeoutDuration))

	line, err := reader.ReadString('\n')
	if err != nil {
		log.Printf("Auth timeout or read error from %s: %v", clientID, err)
		sendResponse(conn, AuthTimeout.String()+"\n")
		return ""
	}
	line = strings.TrimSpace(line)

	var device *Device

	if strings.HasPrefix(line, "PAIR:") {
		// Pairing flow: PAIR:<enrollment_token>:<client_device_id>:<device_name>
		// (legacy clients send PAIR:<enrollment_token>:<device_name> with no id)
		parts := strings.SplitN(line, ":", 4)
		if len(parts) < 2 {
			log.Printf("Invalid pair format from %s", clientID)
			sendResponse(conn, "PAIR:INVALID\n")
			return ""
		}
		enrollToken := strings.TrimSpace(parts[1])
		clientDeviceID := ""
		deviceName := ""
		if len(parts) >= 4 {
			clientDeviceID = strings.TrimSpace(parts[2])
			deviceName = parts[3]
		} else if len(parts) == 3 {
			deviceName = parts[2]
		}

		if !authManager.Validate(enrollToken) {
			log.Printf("Pairing rejected from %s (bad enrollment token)", clientIP)
			sendResponse(conn, "PAIR:FAIL\n")
			return ""
		}

		id, token, err := deviceManager.Register(clientDeviceID, deviceName, clientIP)
		if err != nil {
			log.Printf("Failed to register device for %s: %v", clientID, err)
			sendResponse(conn, "PAIR:ERROR\n")
			return ""
		}

		session, err := sessionManager.CreateSession(clientID)
		if err != nil {
			log.Printf("Failed to create session for %s: %v", clientID, err)
			sendResponse(conn, "PAIR:ERROR\n")
			return ""
		}

		// PAIR:OK:<device_id>:<device_token>:<session_token>
		sendResponse(conn, fmt.Sprintf("PAIR:OK:%s:%s:%s\n", id, token, session.Token))
		conn.SetReadDeadline(time.Time{})
		log.Printf("Paired and authenticated device %s from %s", id, clientIP)
		return session.Token
	}

	// Authentication flow: AUTH:<device_token>
	token, err := ParseAuthMessage(line)
	if err != nil {
		log.Printf("Invalid auth format from %s: %v", clientID, err)
		sendResponse(conn, AuthInvalidFormat.String()+"\n")
		return ""
	}

	device = deviceManager.ValidateToken(token)
	if device == nil {
		log.Printf("Auth failed from %s (unknown or revoked device)", clientIP)
		sendResponse(conn, AuthFailed.String()+"\n")
		return ""
	}
	deviceManager.Touch(device.ID, clientIP)

	session, err := sessionManager.CreateSession(clientID)
	if err != nil {
		log.Printf("Failed to create session for %s: %v", clientID, err)
		sendResponse(conn, "AUTH:ERROR\n")
		return ""
	}

	log.Printf("Auth successful: device %q (%s) from %s", device.Name, device.ID, clientIP)
	sendResponse(conn, fmt.Sprintf("AUTH:OK:%s\n", session.Token))

	conn.SetReadDeadline(time.Time{})
	return session.Token
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
			if err := ValidateMovementSafe(x, y); err != nil {
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
		text, err := SanitizeTextStrict(msg.Payload)
		if err != nil {
			return NewNACKResponse(msg.SeqID, ErrCodeValidation)
		}
		for _, r := range text {
			safeTypeChar(r)
		}

	case "CHAR":
		// Single character input for real-time typing
		if len(msg.Payload) > 0 {
			r := rune(msg.Payload[0])
			// Handle UTF-8 multi-byte characters
			if len(msg.Payload) > 1 {
				runes := []rune(msg.Payload)
				if len(runes) > 0 {
					r = runes[0]
				}
			}
			safeTypeChar(r)
		}

	case "TB":
		safeKeyPress(uinput.KeyBackspace)

	case "SPACE":
		safeKeyPress(uinput.KeySpace)

	case "ENTER":
		safeKeyPress(uinput.KeyEnter)

	case "KEY":
		// Single key press: KEY:F1, KEY:ESC, KEY:TAB
		key, err := keyNameToUinput(msg.Payload)
		if err != nil {
			return NewNACKResponse(msg.SeqID, ErrCodeValidation)
		}
		safeKeyPress(key)

	case "KEYDOWN":
		// Key down (for holding modifier keys): KEYDOWN:CTRL
		key, err := keyNameToUinput(msg.Payload)
		if err != nil {
			return NewNACKResponse(msg.SeqID, ErrCodeValidation)
		}
		safeKeyDown(key)

	case "KEYUP":
		// Key up (release held key): KEYUP:CTRL
		key, err := keyNameToUinput(msg.Payload)
		if err != nil {
			return NewNACKResponse(msg.SeqID, ErrCodeValidation)
		}
		safeKeyUp(key)

	case "COMBO":
		// Key combination: COMBO:CTRL+C, COMBO:CTRL+SHIFT+S
		if err := ValidateKeyCombo(msg.Payload); err != nil {
			return NewNACKResponse(msg.SeqID, ErrCodeValidation)
		}
		if err := executeKeyCombo(msg.Payload); err != nil {
			return NewNACKResponse(msg.SeqID, ErrCodeValidation)
		}

	case "DBLCLICK":
		// Double click: DBLCLICK:left
		if err := ValidateMouseButton(msg.Payload); err != nil {
			return NewNACKResponse(msg.SeqID, ErrCodeValidation)
		}
		safeDoubleClick(msg.Payload)

	case "MOUSEDOWN":
		// Mouse button down (for drag): MOUSEDOWN:left
		if err := ValidateMouseButton(msg.Payload); err != nil {
			return NewNACKResponse(msg.SeqID, ErrCodeValidation)
		}
		safeMouseButtonDown(msg.Payload)

	case "MOUSEUP":
		// Mouse button up (end drag): MOUSEUP:left
		if err := ValidateMouseButton(msg.Payload); err != nil {
			return NewNACKResponse(msg.SeqID, ErrCodeValidation)
		}
		safeMouseButtonUp(msg.Payload)

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
		sessionManager.RevokeClientSessions(clientID)
		log.Printf("Connection closed: %s", clientAddr)
	}()

	reader := bufio.NewReader(conn)

	// Authentication handshake
	sessionToken := authenticateClient(conn, reader)
	if sessionToken == "" {
		return
	}

	// Main command loop
	for {
		// Validate session is still active (checks TTL)
		if sessionManager.ValidateSession(sessionToken) == nil {
			log.Printf("Session expired for %s", clientAddr)
			sendResponse(conn, "SESSION:EXPIRED\n")
			return
		}

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

		// Only trim newlines, preserve spaces in payload
		line = strings.TrimRight(line, "\r\n")
		if strings.TrimSpace(line) == "" {
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

// runDeviceAdmin handles the device-management admin commands and exits.
func runDeviceAdmin(listDevices bool, revoke string, revokeAll bool, cleanup bool) {
	if err := deviceManager.Load(); err != nil {
		log.Fatalf("Failed to load device registry: %v", err)
	}

	if revokeAll {
		n := deviceManager.RevokeAll()
		fmt.Printf("Revoked %d device(s).\n", n)
	}

	if revoke != "" {
		// Match by exact device ID first, then fall back to device name.
		if deviceManager.Revoke(revoke) {
			fmt.Printf("Device %s revoked.\n", revoke)
		} else if n := deviceManager.RevokeByName(revoke); n > 0 {
			fmt.Printf("Revoked %d device(s) named %q.\n", n, revoke)
		} else {
			fmt.Printf("No device found with ID or name %q.\n", revoke)
		}
	}

	if cleanup {
		n := deviceManager.CleanupRevoked()
		fmt.Printf("Removed %d revoked device(s) from the registry.\n", n)
	}

	if listDevices {
		devices := deviceManager.List()
		if len(devices) == 0 {
			fmt.Println("No paired devices.")
			return
		}
		fmt.Printf("%-32s  %-20s  %-8s  %-19s  %s\n", "ID", "NAME", "STATUS", "LAST SEEN", "LAST IP")
		for _, d := range devices {
			status := "active"
			if d.Revoked {
				status = "revoked"
			}
			fmt.Printf("%-32s  %-20s  %-8s  %-19s  %s\n",
				d.ID, d.Name, status, d.LastSeen.Format("2006-01-02 15:04:05"), d.LastIP)
		}
	}
}

func main() {
	// CLI flags. Device-admin commands run without needing /dev/uinput.
	addr := flag.String("addr", HOST, "Bind address (e.g. 0.0.0.0 for all interfaces, 127.0.0.1 for loopback only)")
	port := flag.Int("port", PORT, "TCP port to listen on")
	listDevices := flag.Bool("list-devices", false, "List paired devices and exit")
	revoke := flag.String("revoke", "", "Revoke a paired device by ID or name, then exit")
	revokeAll := flag.Bool("revoke-all", false, "Revoke all paired devices, then exit")
	cleanup := flag.Bool("cleanup", false, "Remove revoked devices from the registry, then exit")
	flag.Parse()

	if *listDevices || *revoke != "" || *revokeAll || *cleanup {
		runDeviceAdmin(*listDevices, *revoke, *revokeAll, *cleanup)
		return
	}

	// Virtual input devices are only needed when actually serving.
	if err := initInputDevices(); err != nil {
		log.Fatalf("%v", err)
	}
	defer keyboard.Close()
	defer mouse.Close()

	// Start rate limiter cleanup (every 5 minutes, remove limiters idle for 10 minutes)
	cleanupStopCh := make(chan struct{})
	defer close(cleanupStopCh)
	rateLimiters.StartCleanup(5*time.Minute, 10*time.Minute, cleanupStopCh)

	// Start session cleanup (every 5 minutes)
	sessionManager.StartCleanup(5*time.Minute, cleanupStopCh)

	// Initialize TLS
	if err := tlsConfig.EnsureCertificates(); err != nil {
		log.Fatalf("Failed to setup TLS certificates: %v", err)
	}

	tlsCfg, err := tlsConfig.LoadTLSConfig()
	if err != nil {
		log.Fatalf("Failed to load TLS config: %v", err)
	}

	// Initialize authentication (enrollment token) and device registry
	if err := authManager.Initialize(); err != nil {
		log.Fatalf("Failed to initialize authentication: %v", err)
	}
	if err := deviceManager.Load(); err != nil {
		log.Fatalf("Failed to load device registry: %v", err)
	}
	// Prune revoked devices once a day.
	deviceManager.StartCleanup(DeviceCleanupInterval, cleanupStopCh)

	// Create TLS listener
	listener, err := tls.Listen("tcp", fmt.Sprintf("%s:%d", *addr, *port), tlsCfg)
	if err != nil {
		log.Fatalf("Failed to start TLS server: %v", err)
	}
	defer listener.Close()

	// Graceful shutdown: close the listener on SIGINT/SIGTERM so Accept() returns,
	// then deferred cleanup (uinput devices, cleanup goroutines) runs on the way out.
	shuttingDown := make(chan struct{})
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP)
	go func() {
		for sig := range sigCh {
			if sig == syscall.SIGHUP {
				// Reload the device registry so out-of-band admin changes
				// (e.g. `AndroControl -revoke ...`) take effect without a restart.
				log.Println("Received SIGHUP — reloading device registry")
				if err := deviceManager.Reload(); err != nil {
					log.Printf("Device registry reload failed: %v", err)
				}
				continue
			}
			log.Printf("Received %s — shutting down gracefully...", sig)
			close(shuttingDown)
			listener.Close()
			return
		}
	}()

	log.Println("════════════════════════════════════════════════════════════════════")
	log.Printf("  AndroControl Server v%s", ProtocolVersion)
	log.Printf("  Listening on %s:%d (TLS)", *addr, *port)
	log.Println("════════════════════════════════════════════════════════════════════")

	// Print QR code for easy mobile connection (includes enrollment token)
	hostname, _ := os.Hostname()
	if hostname == "" {
		hostname = "AndroControl"
	}
	PrintQRCode(hostname, *port, authManager.GetToken())

	// Print certificate info (after auth token for verification)
	tlsConfig.PrintCertificateInfo()

	log.Println("Waiting for connections...")

	for {
		conn, err := listener.Accept()
		if err != nil {
			select {
			case <-shuttingDown:
				log.Println("Listener closed, server stopped")
				return
			default:
				log.Printf("Error accepting connection: %v", err)
				continue
			}
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
