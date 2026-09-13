package io.haifa.agent.auth.localmodel;

import io.haifa.agent.common.io.SecureFilePermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fail-closed current-user filesystem permission policy. */
final class LocalModelAuthFilePermissions {
    void secure(Path path, boolean directory) throws IOException {
        if (directory) SecureFilePermissions.secureDirectory(path);
        else SecureFilePermissions.secureFile(path);
    }

    void rejectSymbolicLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalStateException("Local model auth path must not be a symbolic link");
        }
    }
}
