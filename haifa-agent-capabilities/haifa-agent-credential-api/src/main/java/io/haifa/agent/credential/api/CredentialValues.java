package io.haifa.agent.credential.api;

final class CredentialValues {
    private CredentialValues() {}

    static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
