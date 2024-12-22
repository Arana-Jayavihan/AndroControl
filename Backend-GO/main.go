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

func getKeyCode(char rune) int {
	keyMap := map[rune]int{
		'a': uinput.KeyA, 'b': uinput.KeyB, 'c': uinput.KeyC,
		'd': uinput.KeyD, 'e': uinput.KeyE, 'f': uinput.KeyF,
		'g': uinput.KeyG, 'h': uinput.KeyH, 'i': uinput.KeyI,
		'j': uinput.KeyJ, 'k': uinput.KeyK, 'l': uinput.KeyL,
		'm': uinput.KeyM, 'n': uinput.KeyN, 'o': uinput.KeyO,
		'p': uinput.KeyP, 'q': uinput.KeyQ, 'r': uinput.KeyR,
		's': uinput.KeyS, 't': uinput.KeyT, 'u': uinput.KeyU,
		'v': uinput.KeyV, 'w': uinput.KeyW, 'x': uinput.KeyX,
		'y': uinput.KeyY, 'z': uinput.KeyZ,
	}
	return keyMap[char]
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
