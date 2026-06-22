# mTLS Migration Plan — Option A (pinned self-signed client certificates)

> **Status:** Phases 1–4 implemented. Server side (`tls.go`, `devices.go`, `main.go`,
> `protocol.go`) is complete and tested (`go test` incl. `-race`). Android side
> (`ClientIdentity.java`, `TlsHelper`, `Protocol.establishSession`, `MainActivity`)
> is code-complete and needs a Studio build + on-device test. Protocol bumped to 2.0.
> Remaining follow-up: client-certificate **expiry/rotation** flow.

## Goal
Replace the per-device **bearer token** with a per-device **client certificate** whose
private key lives in the Android Keystore (non-exportable / hardware-backed). The
device proves its identity during the TLS handshake; the server recognizes the device
by the SHA-256 fingerprint of the presented client certificate — mirroring today's
`devices.json` registry, but with cert fingerprints instead of token hashes.

This removes the copyable/replayable device credential entirely. It is **complementary
to** server-cert pinning (the QR-fingerprint fix), not a replacement for it.

## Design
- Client generates an EC key pair + self-signed cert in the Android Keystore.
- TLS: server uses `ClientAuth: tls.RequireAnyClientCert` and validates the peer cert
  in a `VerifyConnection` callback against the device registry (active, not revoked).
- Identity = SHA-256 of the client cert (DER). No CA to operate.
- Enrollment token still gates *who may register a new client cert* (pairing).

## Phases

### Phase 1 — Server registry & TLS
- `devices.go`: add `CertFingerprint string` to `Device`; add `ValidateCert(fp)` /
  `RegisterCert(clientID, fp, name, ip)`; keep revoke/cleanup/active-conn logic.
- `tls.go`: `ClientAuth = tls.RequireAnyClientCert`; add `VerifyConnection` that
  fingerprints `cs.PeerCertificates[0]` and accepts only if `deviceManager` has it
  active. Unknown certs are allowed through TLS (so pairing can run) but flagged.
- Keep TLS 1.2+ and the existing cipher suite list.

### Phase 2 — Server handshake/protocol
- On connect, read the verified peer-cert fingerprint:
  - **Known + active** → authenticated; go straight to the command loop (no `AUTH`).
  - **Unknown** → require `PAIR:<enroll>:<clientid>:<name>`; validate the enrollment
    token; register the *presented* cert's fingerprint; then enter the command loop.
- Remove `AUTH:<token>` and device-token issuance. Bump `ProtocolVersion` to `2.0`.
- Revocation/active-session drop and audit logging unchanged.

### Phase 3 — Android client
- Generate/keep an EC keypair in `AndroidKeyStore` (alias `androcontrol_client`),
  with an auto-generated self-signed cert. Private key never leaves the keystore.
- Build `SSLContext` with a `KeyManager` from the keystore (client cert) + the existing
  pinning `TrustManager` (server cert).
- Pairing: connect (cert presented) → send `PAIR:<enroll>:<clientid>:<name>`.
  No device token to store; drop `devtoken_*` from `SecureStorage`.
- `client_device_id` dedup becomes redundant (the cert is the stable identity).
- "Unpair" = delete the keystore key + revoke the fingerprint server-side.

### Phase 4 — Tests
- Server handshake tests with a generated client cert: pair-then-recognize,
  unknown-cert-requires-pair, revoked-cert-rejected.
- Client: keystore key generation + KeyManager wiring (instrumented).

### Phase 5 — Migration
- Breaking change (pre-launch): existing token-paired devices must re-pair.
  No dual-mode; document the cutover.

## What this fixes vs. leaves open
- **Fixes:** bearer-token theft/replay, token-at-rest/in-logs/in-backup exposure,
  stronger revocation (rejected at handshake).
- **Reduces:** first-use MITM *impact* (no reusable credential to steal).
- **Does NOT fix (handled separately, see below):** server-cert MITM on first use
  (needs QR cert pin), read-length DoS, rate-limit keying, Android log/UI/backup
  hardening, enrollment-token exposure.

## Risks / cost
- Moderate: Android `KeyManager` alias selection from `AndroidKeyStore`; client-cert
  expiry/rotation flow (follow-up); handshake tests need client certs.
- Touches `tls.go`, `devices.go`, `main.go`, `TlsHelper`, `Protocol`, `SecureStorage`.
