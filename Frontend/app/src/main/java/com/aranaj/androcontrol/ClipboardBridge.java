package com.aranaj.androcontrol;

/**
 * Tiny in-process handoff for clipboard text between the transparent
 * {@link ClipboardBridgeActivity} trampoline and {@link MainActivity}. Clipboards can
 * be up to ~1 MB, which is too large to pass reliably through PendingIntent/broadcast
 * extras (Binder transaction limits), so we park the payload here and only pass a
 * trigger through the system.
 */
final class ClipboardBridge {
    private static volatile String incoming; // desktop -> phone: text to set locally
    private static volatile String outgoing; // phone -> desktop: text read from clipboard

    private ClipboardBridge() {
    }

    static void setIncoming(String text) {
        incoming = text;
    }

    static String takeIncoming() {
        String t = incoming;
        incoming = null;
        return t;
    }

    static void setOutgoing(String text) {
        outgoing = text;
    }

    static String takeOutgoing() {
        String t = outgoing;
        outgoing = null;
        return t;
    }
}
