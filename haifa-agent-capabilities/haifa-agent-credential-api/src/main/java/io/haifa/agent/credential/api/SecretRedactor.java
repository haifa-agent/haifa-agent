package io.haifa.agent.credential.api;

@FunctionalInterface
public interface SecretRedactor {
    String redact(String text);

    default AutoCloseable registerScoped(String secret) {
        return () -> {};
    }

    default AutoCloseable registerScoped(java.util.Collection<String> secrets) {
        return () -> {};
    }

    static SecretRedactor noop() {
        return text -> text;
    }
}
