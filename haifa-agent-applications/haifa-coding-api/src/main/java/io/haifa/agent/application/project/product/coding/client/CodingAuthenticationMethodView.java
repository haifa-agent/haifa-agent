package io.haifa.agent.application.project.product.coding.client;

import java.util.Objects;
import java.util.Optional;

/** Safe, secret-free description of one way to connect a model provider. */
public record CodingAuthenticationMethodView(
        String id, Type type, boolean available, Optional<String> unavailableReason) {
    public CodingAuthenticationMethodView {
        id = text(id, "id");
        type = Objects.requireNonNull(type, "type must not be null");
        unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason must not be null")
                .map(value -> text(value, "unavailableReason"));
        if (available && unavailableReason.isPresent()) {
            throw new IllegalArgumentException("available method cannot have an unavailable reason");
        }
        if (!available && unavailableReason.isEmpty()) {
            throw new IllegalArgumentException("unavailable method requires a reason");
        }
    }

    public enum Type {
        OAUTH,
        API_KEY
    }

    private static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
