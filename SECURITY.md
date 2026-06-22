# Security Policy

## Reporting a vulnerability
Please report security issues privately — open a GitHub **security advisory** on the
repository (or a minimal private issue) rather than a public issue. Include steps to
reproduce and affected versions. We aim to acknowledge within a few days.

Do not include working exploits against third-party systems; this project is for
controlling **your own** machine.

## Trust model
AndroControl is **self-hosted**: the Android app controls a server you run on your own
Linux machine. There is no cloud component and the developers receive no data.

- **Transport:** TLS 1.2/1.3, ECDHE-ECDSA (AES-GCM / ChaCha20-Poly1305).
- **Server authentication:** the app pins the server certificate. The setup QR carries
  the cert's SHA-256 fingerprint, so QR-paired servers are pinned with no
  trust-on-first-use window; manual setup falls back to TOFU (fail-closed).
- **Device authentication (mutual TLS):** each device has its own client certificate
  whose private key is generated non-exportably in the Android Keystore. The server
  identifies devices by certificate fingerprint — no bearer token is transmitted or
  stored that could be copied or replayed.
- **Pairing:** gated by a long-lived enrollment token (shown in the QR / console).
  Pairing binds a device's certificate to the server; the token is then discarded on
  the device. Revoke a device with `androcontrol-ctl revoke <id|name>` (revocation is
  enforced at the TLS handshake and drops any live connection).
- **Sessions:** only one device may hold an active control session at a time; other
  devices are refused without disturbing the active session.

## Hardening controls
- Per-IP **lockout** after repeated failed pairing/auth attempts; `[AUDIT]` logging.
- Per-IP command **rate limiting** and connection caps.
- Bounded protocol line length (anti-DoS); strict input validation; the command set is
  a fixed allow-list mapped to `uinput` (no shell execution).
- Server runs as a dedicated unprivileged user with access only to `/dev/uinput`
  (see `Backend-GO/deploy/`).

## Important caveat
Anyone who can reach the port **and** holds a valid credential gains full keyboard/mouse
control of the host. Treat the enrollment token/QR as a secret, run on trusted networks
(or bind to loopback + VPN/SSH tunnel), and rotate the enrollment token with
`androcontrol-ctl regen-token` if it leaks (existing paired devices keep working; only
new pairings need the new token). See `PRIVACY.md` for data handling.
