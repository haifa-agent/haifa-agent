package io.haifa.agent.application.project.tool.web;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

public record WebUrlDecision(boolean allowed, URI normalizedUrl, Optional<String> denialCode) {
    public WebUrlDecision {
        normalizedUrl = Objects.requireNonNull(normalizedUrl, "normalizedUrl").normalize();
        denialCode = Objects.requireNonNull(denialCode, "denialCode");
        if (allowed == denialCode.isPresent()) {
            throw new IllegalArgumentException("allowed decision and denialCode are inconsistent");
        }
    }

    public static WebUrlDecision allow(URI normalizedUrl) {
        return new WebUrlDecision(true, normalizedUrl, Optional.empty());
    }

    public static WebUrlDecision deny(URI normalizedUrl, String code) {
        return new WebUrlDecision(false, normalizedUrl, Optional.of(WebValues.text(code, "denialCode", 128)));
    }
}
