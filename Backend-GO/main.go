package main

import (
	"fmt"
	"log"
	"net"
	"strconv"
	"strings"

	"github.com/bendahl/uinput"
)

const (
	HOST = "0.0.0.0"
	PORT = 5050
)

var keyboard uinput.Keyboard
var mouse uinput.Mouse

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

func handleClient(conn net.Conn) {
	defer conn.Close()

	buffer := make([]byte, 1024)
	for {
		n, err := conn.Read(buffer)
		if err != nil {
			log.Printf("Error reading from connection: %v", err)
			return
		}

		data := strings.TrimSpace(string(buffer[:n]))
		if data == "" {
			continue
		}

		parts := strings.SplitN(data, ":", 2)
		if len(parts) != 2 {
			continue
		}

		command := parts[0]
		payload := parts[1]

		switch command {
		case "M":
			coords := strings.Split(strings.Split(payload, "\\")[0], ",")
			if len(coords) == 2 {
				x, _ := strconv.Atoi(coords[0])
				y, _ := strconv.Atoi(coords[1])
				mouse.Move(int32(x), int32(y))
			}

		case "C":
			switch payload {
			case "left":
				mouse.LeftClick()
			case "right":
				mouse.RightClick()
			case "middle":
				mouse.MiddleClick()
			}

		case "S":
			amount, _ := strconv.Atoi(payload)
			mouse.Wheel(false, int32(-amount))

		case "T":
			for _, r := range payload {
				key, err := asciiToUinput(int(r))

				if err != nil {
					log.Printf("Unsupported ASCII %d: %v", r, err)
					continue
				}

				if int(65) <= int(r) && int(r) <= int(90) {
					keyboard.KeyDown(uinput.KeyLeftshift)
					keyboard.KeyPress(key)
					keyboard.KeyUp(uinput.KeyLeftshift)
				} else {
					keyboard.KeyPress(key)
				}
			}

		case "TB":
			keyboard.KeyPress(uinput.KeyBackspace)

		case "SPACE":
			keyboard.KeyPress(uinput.KeySpace)

		case "ENTER":
			keyboard.KeyPress(uinput.KeyEnter)

		default:
			log.Printf("Unknown command: %s", command)
		}
	}
}

func main() {
	defer keyboard.Close()
	defer mouse.Close()

	listener, err := net.Listen("tcp", fmt.Sprintf("%s:%d", HOST, PORT))
	if err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
	defer listener.Close()

	log.Printf("Server listening on %s:%d", HOST, PORT)

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Printf("Error accepting connection: %v", err)
			continue
		}

		log.Printf("Connection established with %s", conn.RemoteAddr())
		go handleClient(conn)
	}
}
