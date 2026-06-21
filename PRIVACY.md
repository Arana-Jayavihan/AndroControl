# AndroControl — Privacy Policy

_Last updated: 2026-06-22_

AndroControl is a **self-hosted** application: the Android app talks directly to a
server that **you** run on your own computer. The developers operate no servers and
**receive no data** from the app or the server.

## What the app stores (on your device only)
- **Server list** — names, IP addresses and ports you add (encrypted at rest).
- **Authentication material** — pairing tokens and the per-device token issued by
  your server, stored using the Android Keystore (AES-GCM).
- **Preferences** — theme, scroll-bar and haptic settings, and a randomly generated
  per-install device identifier used so re-pairing doesn't create duplicate server
  records.

This data never leaves your device except to authenticate to the servers you
configure. Uninstalling the app removes it.

## Permissions
- **Camera** — used solely to scan the setup QR code. Frames are processed
  on-device (Google ML Kit, offline); no images are stored or transmitted.
- **Internet / Network state** — used only to connect to the servers you configure,
  over TLS.
- **Vibrate** — haptic feedback for on-screen controls.

## Network
All communication uses TLS 1.2/1.3 with certificate pinning (trust-on-first-use).
The app connects only to the server addresses you enter; it contacts no other
endpoints. There are **no analytics, ads, or third-party tracking SDKs**.

## What the server stores (on your machine only)
- A device registry (`devices.json`) containing device names, **hashed** tokens
  (never plaintext), and last-seen timestamps/IP addresses for auditing.
- Local logs, which may include client IP addresses and connection events.

You control this data entirely; it is never sent anywhere.

## Contact
Questions: open an issue at the project repository.
