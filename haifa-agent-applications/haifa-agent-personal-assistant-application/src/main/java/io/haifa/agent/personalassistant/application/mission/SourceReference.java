package io.haifa.agent.personalassistant.application.mission;

import java.net.URI;
import java.util.Objects;

/**
 * Lightweight, code-authoritative reference to an external web source used during a Mission.
 *
 * <p>Unlike Deep Research's formal evidence model, Standard Mission source references do not require
 * cryptographic content digests, multi-source claim verification, or conflict states.
 */
public record SourceReference(String sourceId, String title, String locator) {
    public SourceReference {
        sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null").trim();
        title = Objects.requireNonNull(title, "title must not be null").trim();
        locator = Objects.requireNonNull(locator, "locator must not be null").trim();
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (locator.isBlank()) {
            throw new IllegalArgumentException("locator must not be blank");
        }
        try {
            URI uri = URI.create(locator);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("locator must be an HTTP or HTTPS URL: " + locator);
            }
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("locator is not a valid URI: " + locator, failure);
        }
    }
}
