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
   - Generate an **enrollment (pairing) token** (stored in `auth_token`)
   - Display a QR code for easy mobile setup
   - Start listening on port 5050

#### Command-line options

```
./AndroControl [flags]

  -addr string      Bind address (default "0.0.0.0"; use 127.0.0.1 for loopback only)
  -port int         TCP port to listen on (default 5050)
  -list-devices     List paired devices and exit
  -revoke <id|name> Revoke a paired device by ID or name, then exit
  -revoke-all       Revoke all paired devices, then exit
  -cleanup          Remove revoked devices from the registry, then exit
```

The server stops cleanly on `Ctrl+C` / `SIGTERM`, releasing the virtual input devices.

### Setting up the Android App

1. Build the APK from source or download a release
2. Install on your Android device
3. **Pair via QR code** (recommended):
   - Open the app and tap the QR scanner icon
   - Scan the QR code displayed by the server
   - Verify the certificate fingerprint and accept
4. **Or pair manually**:
   - Add a new server with IP, port, and the pairing token
   - Verify the certificate fingerprint on first connection

On first connection the app exchanges the pairing token for its **own per-device token**
(the pairing token is then discarded on the phone). Each device can be revoked
independently from the server.

### Managing paired devices

```bash
# List devices (id, name, status, last seen, last IP)
./AndroControl -list-devices

# Revoke a device by ID or name — it can no longer connect until re-paired
./AndroControl -revoke <device-id-or-name>

# Revoke every paired device
./AndroControl -revoke-all

# Permanently remove revoked devices from the registry
./AndroControl -cleanup
```

Device records are stored in `devices.json` (token hashes only — never plaintext).
Revoked devices are also **pruned automatically once a day** while the server runs.

> When running as a **service**, the admin commands above edit `devices.json` on
> disk while a separate server process holds the registry in memory. Apply the
> change to the running server without a restart by reloading it (sends `SIGHUP`):
> `sudo systemctl reload androcontrol` (or `kill -HUP <pid>`).

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

### Per-device Authentication
The server generates a long-lived **enrollment (pairing) token** on first run. A device
presents this token once to *pair*; the server then issues that device its **own
per-device token**, which the app stores (Android Keystore, AES-GCM). Per-device tokens:

- can be **revoked individually** (`-revoke <id>`) without affecting other devices,
- are stored on the server as **SHA-256 hashes only** (never plaintext),
- record last-seen time and IP for basic auditing (`-list-devices`).

Anyone with the enrollment token can pair a new device, so treat the QR code / token
as a secret and rotate it (delete `auth_token` and restart) if it leaks.

### Session Management
Each authenticated connection receives a session token with a 30-minute TTL. Sessions are automatically refreshed during active use.

## Network Configuration

- Default port: **5050**
- Protocol: **TCP with TLS**
- Both devices must be on the same network (or have appropriate routing)
- Firewall must allow TCP traffic on the configured port

> **Security note:** anyone who can reach the port and holds a valid token gets full
> keyboard/mouse control of the machine. Run AndroControl only on trusted networks,
> bind to `127.0.0.1` and use a VPN/SSH tunnel for remote access, or restrict the port
> with a firewall. Avoid exposing it directly to the internet.

## Running as a service (systemd)

Sample units are in [`Backend-GO/deploy/`](Backend-GO/deploy/):

```bash
# 1. Load uinput at boot and grant the 'input' group access to it
sudo cp Backend-GO/deploy/uinput.conf      /etc/modules-load.d/uinput.conf
sudo cp Backend-GO/deploy/99-uinput.rules  /etc/udev/rules.d/99-uinput.rules
sudo modprobe uinput
sudo udevadm control --reload-rules && sudo udevadm trigger

# 2. Create a dedicated user and install the binary + data dir
sudo useradd --system --no-create-home --groups input androcontrol
sudo install -Dm755 Backend-GO/AndroControl /usr/local/bin/AndroControl
sudo install -d -o androcontrol -g androcontrol /var/lib/androcontrol

# 3. Install and start the service
sudo cp Backend-GO/deploy/androcontrol.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now androcontrol
sudo journalctl -u androcontrol -f   # view the QR code / token / fingerprint
```

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
