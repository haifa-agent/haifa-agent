package io.haifa.agent.credential.api;

@FunctionalInterface
public interface SecretRedactor {
    String redact(String text);

    static SecretRedactor noop() {
        return text -> text;
    }
}
