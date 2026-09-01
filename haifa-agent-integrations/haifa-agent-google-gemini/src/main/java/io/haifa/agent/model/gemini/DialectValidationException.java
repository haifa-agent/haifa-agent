package io.haifa.agent.model.gemini;

import io.haifa.agent.model.api.ModelErrorCategory;
import java.util.Objects;

final class DialectValidationException extends IllegalArgumentException {
    private final String providerCode;
    private final ModelErrorCategory category;

    DialectValidationException(String providerCode, String message) {
        this(providerCode, ModelErrorCategory.INVALID_REQUEST, message);
    }

    DialectValidationException(String providerCode, ModelErrorCategory category, String message) {
        super(message);
        this.providerCode = Objects.requireNonNull(providerCode, "providerCode must not be null");
        this.category = Objects.requireNonNull(category, "category must not be null");
    }

    String providerCode() {
        return providerCode;
    }

    ModelErrorCategory category() {
        return category;
    }
}
