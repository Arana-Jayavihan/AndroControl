# Changelog

All notable changes to AndroControl are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The wire protocol version is
tracked separately as `ProtocolVersion` in `Backend-GO/protocol.go` (currently **2.0**).

## [Unreleased]

### Added
- **Mutual TLS device authentication.** Each device generates an EC key pair and
  self-signed client certificate in the Android Keystore (non-exportable) and proves its
  identity during the TLS handshake. The server identifies devices by certificate
  fingerprint (`devices.json`).
- **QR-pinned server certificates.** The setup QR carries the server cert's SHA-256
  fingerprint, so QR-paired servers are pinned immediately with no trust-on-first-use
  window. Manual setup falls back to fail-closed TOFU.
- **Single active session.** Only one device controls the host at a time; a different
  device is refused (`AUTH:BUSY`) without disturbing the active session, while the same
  device reconnecting reclaims its slot.
- **`androcontrol-ctl` management CLI** — `qr`, `regen-token`, `list`,
  `revoke <id|name>`, `revoke-all`, `rename`, `prune-inactive <days>`, `cleanup`.
  Revocation drops any live connection; `regen-token` rotates the enrollment token and
  a running service reloads it on SIGHUP without dropping paired devices.
- **Per-IP auth throttling and lockout** with `[AUDIT]` logging of pairing/auth events.
- **Connect/disconnect event hook** (`-on-event` / `services.androcontrol.onEvent`) — runs
  a configurable command on each device connect/disconnect with details in
  `ANDROCONTROL_*` env vars (wire it to `notify-send`, `ntfy`, a webhook, etc.).
  Disconnects also log session duration as an `[AUDIT]` line.
- **Foreground service** keeps the connection alive while the app is backgrounded.
- Graceful shutdown (SIGINT/SIGTERM) and live device-registry reload (SIGHUP).

### Changed
- Replaced the per-device bearer token with mutual-TLS client certificates; the
  enrollment token is now used **only** for one-time pairing and is discarded on the
  device afterward.
- Decoupled the TLS handshake deadline from the app-auth timeout (generous
  `HandshakeTimeout`) so an interactive certificate confirmation can't be killed
  mid-handshake; handshake failures are logged at WARN.
- Consolidated the standalone `androcontrol-qr` helper into `androcontrol-ctl qr` (which
  now emits the cert fingerprint for pinning).

### Fixed
- Client certificate was not presented on Conscrypt's SSLEngine path — switched to an
  `X509ExtendedKeyManager` overriding both the socket and engine alias selectors.
- mTLS handshake failed signing the CertificateVerify — the Keystore key now authorizes
  `DIGEST_NONE` (Conscrypt signs raw `NONEwithECDSA`).
- The on-screen keyboard sometimes auto-opened when returning the app from the
  background — set `windowSoftInputMode="stateAlwaysHidden"` on the main activity.

### Removed
- Dead code across the Go backend and Android app (unused functions, methods, imports,
  and string resources).

### Security
- See [SECURITY.md](SECURITY.md) for the trust model, hardening controls, and
  vulnerability-disclosure process. See [PRIVACY.md](PRIVACY.md) for data handling.
