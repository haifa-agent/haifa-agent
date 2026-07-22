package io.haifa.agent.credential.api;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

final class CredentialValues {
    private CredentialValues() {}

    static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static <T> Set<T> set(Set<T> value, String name) {
        Objects.requireNonNull(value, name);
        var copy = new LinkedHashSet<T>();
        for (T element : value) {
            copy.add(Objects.requireNonNull(element, name + " element"));
        }
        return Set.copyOf(copy);
    }
}
