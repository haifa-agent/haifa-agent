package io.haifa.agent.credential.api;

import java.time.Instant;

public interface CredentialLease extends AutoCloseable {
    CredentialReference reference();

    Instant expiresAt();

    boolean isClosed();

    <T> T use(SecretFunction<T> action);

    @Override
    void close();
}
