package io.haifa.agent.application.project.product.coding.client;

import java.net.URI;
import java.util.Objects;

/** Ephemeral browser-login instruction. It must not be persisted or logged. */
public record CodingBrowserLoginView(URI authorizationUri, long expiresAtEpochMillis) {
    public CodingBrowserLoginView {
        authorizationUri = Objects.requireNonNull(authorizationUri, "authorizationUri must not be null");
        if (!("https".equalsIgnoreCase(authorizationUri.getScheme())
                        || "http".equalsIgnoreCase(authorizationUri.getScheme()))
                || authorizationUri.getHost() == null
                || authorizationUri.getRawUserInfo() != null
                || authorizationUri.getRawFragment() != null
                || (authorizationUri.getRawQuery() != null
                        && (authorizationUri.getRawQuery().length() > 8 * 1024
                                || authorizationUri.getRawQuery().indexOf('\0') >= 0))
                || expiresAtEpochMillis < 0) {
            throw new IllegalArgumentException("browser login instruction is invalid");
        }
    }

    @Override
    public String toString() {
        return "CodingBrowserLoginView[authorizationUri=[REDACTED_AUTH_URL], expiresAtEpochMillis="
                + expiresAtEpochMillis + "]";
    }
}
