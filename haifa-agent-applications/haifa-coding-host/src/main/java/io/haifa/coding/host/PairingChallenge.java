package io.haifa.coding.host;
/** Owner-only one-time proof. Its value must not enter URLs, generic diagnostics or persistent storage. */
public record PairingChallenge(String value, String hostIncarnation, long expiresAtEpochMillis) {
    @Override
    public String toString() {
        return "PairingChallenge[redacted]";
    }
}
