# AndroControl

AndroControl is a secure remote control application that allows you to control your Linux system's mouse and keyboard from your Android smartphone.

## Features

- **Mouse Control**: Move cursor, left/right/middle click, double-click, drag, and scroll
- **Keyboard Control**: Full QWERTY keyboard with modifier keys (Ctrl, Alt, Shift, Super/Win)
- **Special Keys**: Function keys (F1-F12), navigation keys, Tab, Escape, etc.
- **Key Combos**: Support for keyboard shortcuts like Ctrl+C, Alt+Tab, etc.
- **TLS Encryption**: All communication is encrypted using TLS 1.2/1.3
- **TOFU Security**: Trust-On-First-Use certificate pinning with user confirmation
- **Token Authentication**: Secure token-based authentication
- **QR Code Setup**: Scan QR code from server for easy connection setup
- **Multiple Servers**: Save and manage multiple server configurations
- **Haptic Feedback**: Vibration feedback for button presses
- **Heartbeat**: Connection health monitoring with automatic reconnection

## Architecture

The solution follows a client-server architecture:

- **Backend (Go)**: A lightweight server running on the target Linux system that receives control signals over TLS-encrypted TCP and executes them using uinput
- **Frontend (Android)**: A Material Design app that sends mouse and keyboard inputs to the server

## Requirements

### Server (Linux)
- Linux system with uinput kernel module
- Go 1.21+ (for building from source)

### Client (Android)
- Android 6.0 (API 23) or higher
- Camera permission (for QR code scanning)
- Network access to the server

## Installation

### Setting up the Server

1. **Install uinput kernel module**:
   ```bash
   sudo modprobe -i uinput
   ```
   To load on boot, add `uinput` to `/etc/modules`.

2. **Build and run the server**:
   ```bash
   cd Backend-GO
   go build -o AndroControl
   ./AndroControl
   ```

3. **First run**: The server will:
   - Generate TLS certificates (stored in `certs/`)
   - Generate an authentication token (stored in `auth_token`)
   - Display a QR code for easy mobile setup
   - Start listening on port 5050

### Setting up the Android App

1. Build the APK from source or download a release
2. Install on your Android device
3. **Connect via QR code** (recommended):
   - Open the app and tap the QR scanner icon
   - Scan the QR code displayed by the server
   - Verify the certificate fingerprint and accept
4. **Or connect manually**:
   - Add a new server with IP, port, and token
   - Verify the certificate fingerprint on first connection

## Usage

### Mouse Controls
- **Swipe** on the touchpad to move the cursor
- **Tap** for left click
- **Long press** for right click
- **Double tap** for double click
- **Two-finger swipe** for scrolling
- Use the **L/M/R buttons** for click and drag operations

### Keyboard Controls
- Tap the **Keyboard** button to show the keyboard panel
- Use the on-screen QWERTY keyboard or the native keyboard
- Toggle **Ctrl/Alt/Shift/Win** modifiers for key combinations
- Special keys: Tab, Esc, Arrow keys, Home, End, Delete, F1-F12

## Security

### TLS Encryption
All communication between the app and server is encrypted using TLS 1.2 or 1.3. The server generates a self-signed certificate on first run.

### Certificate Pinning (TOFU)
On first connection, the app displays the server's certificate fingerprint for verification. Once accepted, the fingerprint is saved and verified on subsequent connections. If the certificate changes, you'll receive a warning.

### Token Authentication
The server generates a secure random token on first run. This token must be provided by the client to authenticate. Tokens are stored securely using Android Keystore encryption.

### Session Management
Each authenticated connection receives a session token with a 30-minute TTL. Sessions are automatically refreshed during active use.

## Network Configuration

- Default port: **5050**
- Protocol: **TCP with TLS**
- Both devices must be on the same network (or have appropriate routing)
- Firewall must allow TCP traffic on the configured port

## Building from Source

### Backend
```bash
cd Backend-GO
go build -o AndroControl
```

### Frontend
```bash
cd Frontend
./gradlew assembleDebug
```

The APK will be in `Frontend/app/build/outputs/apk/debug/`.

## Project Structure

```
AndroControl/
├── Backend-GO/           # Go server
│   ├── main.go          # Main entry point, uinput handling
│   ├── auth.go          # Token authentication
│   ├── session.go       # Session management
│   ├── tls.go           # TLS certificate handling
│   ├── protocol.go      # Command protocol
│   ├── validation.go    # Input validation
│   ├── ratelimit.go     # Rate limiting
│   ├── qrcode.go        # QR code generation
│   └── connmanager.go   # Connection management
├── Frontend/             # Android app
│   └── app/src/main/java/com/aranaj/androcontrol/
│       ├── MainActivity.java      # Main UI and controls
│       ├── TlsHelper.java         # TLS/TOFU implementation
│       ├── SecureStorage.java     # Encrypted storage
│       ├── Protocol.java          # Communication protocol
│       ├── HeartbeatManager.java  # Connection health
│       ├── Server.java            # Server model
│       ├── ServerManager.java     # Server persistence
│       └── QRScannerActivity.java # QR code scanning
└── Assets/               # Screenshots and resources
```

## Troubleshooting

### Server won't start
- Ensure uinput module is loaded: `lsmod | grep uinput`
- Check if port 5050 is available: `ss -tlnp | grep 5050`
- Run with sudo if uinput permissions are insufficient

### App can't connect
- Verify both devices are on the same network
- Check firewall settings on the server
- Ensure the correct IP address and port are configured

### Certificate errors
- If you regenerated server certificates, clear the saved fingerprint in the app
- Go to server settings and delete/re-add the server

### Input not working
- Ensure the server has permissions to use uinput
- Check server logs for error messages

## License

See [LICENSE](LICENSE) file.

## Disclaimer

This software is provided as-is. Use at your own risk. The authors are not responsible for any damages or misuse of this application.
