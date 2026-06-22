package main

import (
	"encoding/json"
	"fmt"
	"net"
	"strings"

	"github.com/skip2/go-qrcode"
)

// ServerInfo contains the connection information for QR code
type ServerInfo struct {
	Name        string `json:"name"`
	IP          string `json:"ip"`
	Port        int    `json:"port"`
	Token       string `json:"token"`
	Fingerprint string `json:"fp,omitempty"` // SHA-256 of the server cert (hex) for pinning
}

// GetLocalIP returns the local IP address of the machine
func GetLocalIP() string {
	interfaces, err := net.Interfaces()
	if err != nil {
		return "127.0.0.1"
	}

	for _, iface := range interfaces {
		// Skip loopback and down interfaces
		if iface.Flags&net.FlagLoopback != 0 || iface.Flags&net.FlagUp == 0 {
			continue
		}

		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}

		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}

			// Skip loopback and IPv6
			if ip == nil || ip.IsLoopback() || ip.To4() == nil {
				continue
			}

			return ip.String()
		}
	}

	return "127.0.0.1"
}

// GenerateServerInfoJSON creates the JSON payload for the QR code
func GenerateServerInfoJSON(name string, ip string, port int, token string, fingerprint string) (string, error) {
	info := ServerInfo{
		Name:        name,
		IP:          ip,
		Port:        port,
		Token:       token,
		Fingerprint: fingerprint,
	}

	data, err := json.Marshal(info)
	if err != nil {
		return "", fmt.Errorf("failed to marshal server info: %w", err)
	}

	return string(data), nil
}

// GenerateQRCodeASCII creates an ASCII representation of a QR code
func GenerateQRCodeASCII(data string) (string, error) {
	qr, err := qrcode.New(data, qrcode.Medium)
	if err != nil {
		return "", fmt.Errorf("failed to generate QR code: %w", err)
	}

	bitmap := qr.Bitmap()
	size := len(bitmap)

	var sb strings.Builder

	// Use Unicode block characters for better resolution
	// Process two rows at a time to use half-block characters
	for y := 0; y < size; y += 2 {
		for x := 0; x < size; x++ {
			top := bitmap[y][x]
			bottom := false
			if y+1 < size {
				bottom = bitmap[y+1][x]
			}

			// In QR codes: true = black (module), false = white (background)
			// For terminal: we want black modules to be visible
			if top && bottom {
				sb.WriteString("█") // Full block
			} else if top {
				sb.WriteString("▀") // Upper half block
			} else if bottom {
				sb.WriteString("▄") // Lower half block
			} else {
				sb.WriteString(" ") // Space (white)
			}
		}
		sb.WriteString("\n")
	}

	return sb.String(), nil
}

// PrintQRCode prints the QR code with connection info to the terminal.
// fingerprint is the server cert SHA-256 (hex); embedding it lets the app pin the
// certificate on first connect instead of trusting it blindly (TOFU).
func PrintQRCode(name string, port int, token string, fingerprint string) {
	ip := GetLocalIP()

	jsonData, err := GenerateServerInfoJSON(name, ip, port, token, fingerprint)
	if err != nil {
		fmt.Printf("Failed to generate QR data: %v\n", err)
		return
	}

	qrASCII, err := GenerateQRCodeASCII(jsonData)
	if err != nil {
		fmt.Printf("Failed to generate QR code: %v\n", err)
		// Fall back to just printing the info
		fmt.Println()
		fmt.Println("╔══════════════════════════════════════════════════════════════════╗")
		fmt.Println("║                       CONNECTION INFO                            ║")
		fmt.Println("╠══════════════════════════════════════════════════════════════════╣")
		fmt.Printf("║  Server: %-56s  ║\n", name)
		fmt.Printf("║  IP:     %-56s  ║\n", ip)
		fmt.Printf("║  Port:   %-56d  ║\n", port)
		fmt.Printf("║  Token:  %-56s  ║\n", token)
		fmt.Println("╚══════════════════════════════════════════════════════════════════╝")
		fmt.Println()
		return
	}

	fmt.Println()
	fmt.Println("╔══════════════════════════════════════════════════════════════════╗")
	fmt.Println("║              SCAN QR CODE WITH ANDROCONTROL APP                  ║")
	fmt.Println("╠══════════════════════════════════════════════════════════════════╣")

	lines := strings.Split(strings.TrimRight(qrASCII, "\n"), "\n")
	for _, line := range lines {
		// Center the QR code
		lineRunes := []rune(line)
		padding := (66 - len(lineRunes)) / 2
		if padding < 0 {
			padding = 0
		}
		rightPadding := 66 - padding - len(lineRunes)
		if rightPadding < 0 {
			rightPadding = 0
		}
		fmt.Printf("║%s%s%s║\n", strings.Repeat(" ", padding), line, strings.Repeat(" ", rightPadding))
	}

	fmt.Println("╠══════════════════════════════════════════════════════════════════╣")
	fmt.Printf("║  Server: %-56s ║\n", name)
	fmt.Printf("║  IP:     %-56s ║\n", ip)
	fmt.Printf("║  Port:   %-56d ║\n", port)
	fmt.Println("╠══════════════════════════════════════════════════════════════════╣")
	fmt.Println("║  Manual setup - enter this token in the app:                     ║")

	// Split token into multiple lines if needed
	tokenLen := len(token)
	if tokenLen <= 60 {
		fmt.Printf("║  %s%s  ║\n", token, strings.Repeat(" ", 62-tokenLen))
	} else {
		fmt.Printf("║  %s  ║\n", token[:60])
		fmt.Printf("║  %s%s  ║\n", token[60:], strings.Repeat(" ", 62-len(token[60:])))
	}

	fmt.Println("╚══════════════════════════════════════════════════════════════════╝")
	fmt.Println()
}
