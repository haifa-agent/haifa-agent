package io.haifa.agent.auth.localmodel;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Low-level boundary for Windows Credential Manager target operations. */
public interface WindowsCredentialManagerClient {
    /** Maximum credential blob size in bytes defined by Windows Credential Manager (5 * 512). */
    int MAX_CREDENTIAL_BLOB_SIZE = 5 * 512;

    Optional<String> read(String targetName);

    void write(String targetName, String secret);

    boolean delete(String targetName);

    List<String> listTargets(String prefixFilter);

    static WindowsCredentialManagerClient defaultClient() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return NativeWindowsCredentialManagerClient.INSTANCE;
        }
        return UnavailableWindowsCredentialManagerClient.INSTANCE;
    }
}
