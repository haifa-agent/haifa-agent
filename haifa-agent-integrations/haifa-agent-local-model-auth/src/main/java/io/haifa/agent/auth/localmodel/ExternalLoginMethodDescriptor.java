package io.haifa.agent.auth.localmodel;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Safe UI descriptor. Registration endpoints and client identifiers are deliberately absent. */
public record ExternalLoginMethodDescriptor(
        ExternalLoginMethodId methodId,
        String displayName,
        Set<ExternalLoginMode> supportedModes,
        boolean unofficial,
        Optional<String> reasonCode) {
    public ExternalLoginMethodDescriptor {
        methodId = Objects.requireNonNull(methodId, "methodId must not be null");
        displayName = safeText(displayName, "displayName", 96);
        supportedModes = Set.copyOf(Objects.requireNonNull(supportedModes, "supportedModes must not be null"));
        if (supportedModes.isEmpty()) throw new IllegalArgumentException("supportedModes must not be empty");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null")
                .map(value -> safeText(value, "reasonCode", 96));
    }

    static String safeText(String value, String field, int limit) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()
                || normalized.length() > limit
                || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
