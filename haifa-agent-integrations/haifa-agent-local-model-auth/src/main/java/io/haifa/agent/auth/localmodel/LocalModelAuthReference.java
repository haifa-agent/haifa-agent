package io.haifa.agent.auth.localmodel;

import java.util.Locale;
import java.util.Objects;

/** Canonical reference to one locally managed model credential. */
public record LocalModelAuthReference(String value, String providerId, String slot) {
    private static final String PREFIX = "model-auth://";

    public LocalModelAuthReference {
        value = Objects.requireNonNull(value, "value must not be null");
        providerId = Objects.requireNonNull(providerId, "providerId must not be null");
        slot = Objects.requireNonNull(slot, "slot must not be null");
        if (!providerId.matches("[a-z][a-z0-9-]{0,63}")
                || !slot.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || slot.contains("..")
                || !value.equals(PREFIX + providerId + "/" + slot)
                || value.length() > 128) {
            throw new IllegalArgumentException("local model auth reference is invalid");
        }
    }

    public static LocalModelAuthReference parse(String input) {
        String value = Objects.requireNonNull(input, "input must not be null").trim();
        if (value.length() > 128
                || !value.startsWith(PREFIX)
                || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0
                || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("local model auth reference is invalid");
        }
        String remainder = value.substring(PREFIX.length());
        int separator = remainder.indexOf('/');
        if (separator < 1 || separator != remainder.lastIndexOf('/') || separator == remainder.length() - 1) {
            throw new IllegalArgumentException("local model auth reference is invalid");
        }
        String provider = remainder.substring(0, separator).toLowerCase(Locale.ROOT);
        String slot = remainder.substring(separator + 1);
        if (!provider.matches("[a-z][a-z0-9-]{0,63}")
                || !slot.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || "..".equals(slot)
                || slot.contains("..")) {
            throw new IllegalArgumentException("local model auth reference is invalid");
        }
        return new LocalModelAuthReference(PREFIX + provider + "/" + slot, provider, slot);
    }

    @Override
    public String toString() {
        return value;
    }
}
