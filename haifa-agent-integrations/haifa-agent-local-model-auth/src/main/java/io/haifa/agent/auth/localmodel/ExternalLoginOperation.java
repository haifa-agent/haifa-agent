package io.haifa.agent.auth.localmodel;

/** One bounded, single-use external login operation. */
public interface ExternalLoginOperation extends AutoCloseable {
    ExternalLoginAttemptSnapshot snapshot();

    StoredExternalCredential execute();

    void cancel();

    @Override
    void close();
}
