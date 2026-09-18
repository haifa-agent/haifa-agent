package io.haifa.agent.application.project.product.coding;

import java.util.Objects;

final class CodingProductValues {
    private CodingProductValues() {}

    static String requireText(String value, String field, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds the maximum length");
        }
        if (normalized.indexOf('\0') >= 0) throw new IllegalArgumentException(field + " contains NUL");
        return normalized;
    }
}
