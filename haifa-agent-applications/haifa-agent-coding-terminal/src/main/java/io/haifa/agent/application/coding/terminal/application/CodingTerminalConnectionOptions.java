package io.haifa.agent.application.coding.terminal.application;

import java.util.Optional;

/**
 * Immutable UI-layer capability input supplied by the composition root.
 *
 * <p>The terminal does not discover transport capabilities by itself. Embedded mode keeps the existing local
 * behavior; a Remote v1 composition supplies {@link #remoteV1()} so the UI can refuse unsupported actions before
 * collecting a secret or pretending a disabled stream exists.
 */
public final class CodingTerminalConnectionOptions {
    private static final CodingTerminalConnectionOptions EMBEDDED =
            new CodingTerminalConnectionOptions(true, true, true);
    private static final CodingTerminalConnectionOptions REMOTE_V1 =
            new CodingTerminalConnectionOptions(false, false, false, Optional.of("unavailable (older Host)"));

    private final Optional<String> executionLocation;
    private final boolean toolOutputPreviewEnabled;
    private final boolean sessionConsentEnabled;
    private final boolean credentialSetupEnabled;

    private CodingTerminalConnectionOptions(
            boolean toolOutputPreviewEnabled, boolean sessionConsentEnabled, boolean credentialSetupEnabled) {
        this(toolOutputPreviewEnabled, sessionConsentEnabled, credentialSetupEnabled, Optional.empty());
    }

    private CodingTerminalConnectionOptions(
            boolean toolOutputPreviewEnabled,
            boolean sessionConsentEnabled,
            boolean credentialSetupEnabled,
            Optional<String> executionLocation) {
        this.executionLocation = executionLocation;
        this.toolOutputPreviewEnabled = toolOutputPreviewEnabled;
        this.sessionConsentEnabled = sessionConsentEnabled;
        this.credentialSetupEnabled = credentialSetupEnabled;
    }

    /** Local embedded behavior, unchanged. */
    public static CodingTerminalConnectionOptions embedded() {
        return EMBEDDED;
    }

    /**
     * Remote v1: Host owns Runtime, preview and session consent; credential setup stays on the Host's trusted
     * interface. Ordinary per-interaction approve/reject and authenticated logout remain available.
     */
    public static CodingTerminalConnectionOptions remoteV1() {
        return REMOTE_V1;
    }

    /** Remote display-only execution root, supplied by the Host; never resolved by the terminal. */
    public static CodingTerminalConnectionOptions remoteV1(String executionLocation) {
        if (executionLocation == null || executionLocation.isBlank()) return REMOTE_V1;
        if (executionLocation.length() > 4096 || executionLocation.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid execution location display value");
        }
        return new CodingTerminalConnectionOptions(false, false, false, Optional.of(executionLocation));
    }

    public Optional<String> executionLocation() {
        return executionLocation;
    }

    /** Whether the transient Tool output preview stream may be subscribed. */
    public boolean toolOutputPreviewEnabled() {
        return toolOutputPreviewEnabled;
    }

    /** Whether "Allow for this session" and its reset are available. Always depends on the returned prompt. */
    public boolean sessionConsentEnabled() {
        return sessionConsentEnabled;
    }

    /** Whether API key/browser/device sign-in may run in this UI process. */
    public boolean credentialSetupEnabled() {
        return credentialSetupEnabled;
    }
}
