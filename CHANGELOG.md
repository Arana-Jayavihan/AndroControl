# Changelog

All notable changes to AndroControl are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The wire protocol version is
tracked separately as `ProtocolVersion` in `Backend-GO/protocol.go` (currently **2.0**).

## [Unreleased]

## [1.1.1] - 2026-06-24

### Added
- **File transfer** with the desktop (opt-in: Settings → File transfer), **up to 5 MB per
  file**.
  - **Android → desktop:** share a file ("Send via AndroControl") → saved to the
    desktop's `~/AndroControl/received/`.
  - **Desktop → Android:** `androcontrol-clip send <file…>` (or a Thunar right-click
    action) → saved to `Downloads/AndroControl/` on the phone.
  - Each transfer is confirmed on the receiving side; folders/multi-selections are zipped
    on the fly. Carried on a dedicated mTLS data port (`-data-port`), kept off the
    input/control stream and paced to leave the link headroom. Larger files await
    resumable transfers; for now they're capped at 5 MB for reliability.

### Changed
- The ongoing connection notification now persists for the whole session: if it's
  dismissed (Android 13+ lets users swipe away foreground-service notifications), it is
  re-posted while the connection is still active.

## [1.1.0] - 2026-06-24

### Added
- **Bidirectional clipboard sync** between the phone and the desktop while connected
  (opt-in: Settings → Clipboard sync).
  - **Desktop → phone:** when the app is foreground the clipboard is set directly; when
    backgrounded a "Clipboard from desktop — tap to copy" notification appears (generic
    text, hidden on the lockscreen) that sets the clipboard via a momentary transparent
    activity on tap.
  - **Phone → desktop:** auto-synced while the app is foreground; when backgrounded, a
    "Send clipboard" action on the connection notification pushes the phone's clipboard.
  - The hardened server only **relays** clipboard text over a loopback channel
    (`-clip-port`); a per-user desktop agent (`androcontrol-clip`) does the actual
    clipboard access using wl-clipboard (Wayland) or xclip (X11), since the sandboxed
    server can't reach the display server. Text only, 1 MB cap, echo-suppressed, and
    never logged. NixOS: `services.androcontrol.clipboardSync = true`; other distros: a
    portable `androcontrol-clip` binary + systemd `--user` unit.

## [1.0.5] - 2026-06-23

### Changed
- **Conflating sender.** Outbound commands are now drained by a dedicated sender thread,
  and consecutive pointer moves are merged (deltas summed) while queued. A burst of input
  can no longer grow an unbounded backlog or monopolise the writer lock, so input latency
  stays bounded and a higher update rate never feels laggier.
- The client socket send buffer is capped so back-pressure surfaces in the app's
  (conflating) queue instead of the kernel hoarding seconds of move packets a slow link
  can't drain — which had shown up as growing lag.
- The pointer update-rate setting is now discrete and **capped at 125 Hz**
  (60 / 80 / 100 / 125 Hz) instead of a continuous slider up to 250 Hz; rates beyond
  ~125 Hz are pointless over Wi-Fi and only risk congestion.

### Fixed
- A congested-but-alive link no longer drops the session. The heartbeat now counts a
  successful send or receive as liveness, so it only times out on a genuine connectivity
  gap rather than transient Wi-Fi congestion.
- The server now releases any mouse button or key left held when a connection drops
  mid-press (a drag or held modifier), so the next session never inherits a stuck input.

## [1.0.4] - 2026-06-23

### Fixed
- High pointer update rates could pause and then disconnect the session. The per-IP rate
  limit (100/s) throttled fast pointer movement, and because heartbeat `PING`s shared
  that limit, a sustained drag starved the keep-alive until the client's heartbeat timed
  out. `PING`/`VERSION` are now exempt from rate limiting, and the input rate limit was
  raised (300/s, burst 400) to cover the update-rate slider's full range.

## [1.0.3] - 2026-06-23

### Added
- **Pointer update-rate setting** (Settings → Performance): a slider that adjusts how
  often pointer movements are sent (4–16 ms of coalescing), shown to the user in Hz
  (≈63–250 Hz). Higher rate = smoother and lower-latency; lower = less traffic/battery.

## [1.0.2] - 2026-06-23

### Changed
- **Lower input latency.** Disabled Nagle's algorithm (`TCP_NODELAY`) on both the
  Android client socket and the server-accepted connection, so small, frequent input
  events (mouse moves, keystrokes) are sent immediately instead of being coalesced.
- The server no longer returns an `ACK` for fire-and-forget input commands (which the
  client discards anyway), eliminating roughly one return packet per mouse move and the
  associated delayed-ACK round trip.
- Reduced the touchpad movement threshold (5 px to 3 px) for finer pointer precision.

## [1.0.0] - 2026-06-23

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
- **Session audit logging** — `[AUDIT] auth_ok`/`pair_ok` on connect and `[AUDIT]
  disconnect` (with session duration) on close.
- **Desktop connect/disconnect notifications** delivered from the user side (the server
  is sandboxed): a NixOS module toggle `services.androcontrol.desktopNotifications`, plus
  a portable `Backend-GO/deploy/androcontrol-notify` script + systemd `--user` unit for
  any distro. Both watch the service journal and pop `notify-send`.
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

### Release
- Tagged `vX.Y.Z` builds now publish the Linux server binaries **and the Android APK**
  (signed when keystore secrets are configured, otherwise a debug APK), with the
  changelog section as the release notes.

### Security
- See [SECURITY.md](SECURITY.md) for the trust model, hardening controls, and
  vulnerability-disclosure process. See [PRIVACY.md](PRIVACY.md) for data handling.
