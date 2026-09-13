package io.haifa.agent.auth.localmodel;

import java.util.List;
import java.util.Optional;

/** Fallback client used when operating system does not provide Windows Credential Manager. */
public final class UnavailableWindowsCredentialManagerClient implements WindowsCredentialManagerClient {
    public static final UnavailableWindowsCredentialManagerClient INSTANCE =
            new UnavailableWindowsCredentialManagerClient();

    private UnavailableWindowsCredentialManagerClient() {}

    @Override
    public Optional<String> read(String targetName) {
        throw unavailable();
    }

    @Override
    public void write(String targetName, String secret) {
        throw unavailable();
    }

    @Override
    public boolean delete(String targetName) {
        throw unavailable();
    }

    @Override
    public List<String> listTargets(String prefixFilter) {
        throw unavailable();
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException(
                "OS_CREDENTIAL_STORE_UNAVAILABLE: system credential store is unavailable on this operating system");
    }
}
