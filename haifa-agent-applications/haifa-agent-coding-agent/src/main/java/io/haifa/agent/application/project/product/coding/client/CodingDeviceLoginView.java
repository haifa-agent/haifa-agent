package io.haifa.agent.application.project.product.coding.client;

import java.net.URI;
import java.util.Objects;

/** Safe, short-lived device-login instruction. It contains no device auth id or token. */
public record CodingDeviceLoginView(URI verificationUri, String userCode, long expiresAtEpochMillis) {
    public CodingDeviceLoginView {
        verificationUri = Objects.requireNonNull(verificationUri, "verificationUri must not be null");
        if (!"https".equalsIgnoreCase(verificationUri.getScheme())
                && !"http".equalsIgnoreCase(verificationUri.getScheme())) {
            throw new IllegalArgumentException("verificationUri scheme is unsupported");
        }
        userCode = Objects.requireNonNull(userCode, "userCode must not be null").trim();
        if (!userCode.matches("[A-Za-z0-9-]{2,32}") || expiresAtEpochMillis < 0) {
            throw new IllegalArgumentException("device login instruction is invalid");
        }
    }
}
