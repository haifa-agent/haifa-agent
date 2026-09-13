package io.haifa.agent.auth.localmodel;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Low-level boundary for Windows Credential Manager target operations. */
public interface WindowsCredentialManagerClient {
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
