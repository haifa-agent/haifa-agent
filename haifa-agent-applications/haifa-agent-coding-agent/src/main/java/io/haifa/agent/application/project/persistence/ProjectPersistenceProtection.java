package io.haifa.agent.application.project.persistence;

import java.util.Locale;

public enum ProjectPersistenceProtection {
    NONE,
    AES_GCM;

    public static ProjectPersistenceProtection parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("persistence protection must be NONE or AES_GCM", exception);
        }
    }
}
