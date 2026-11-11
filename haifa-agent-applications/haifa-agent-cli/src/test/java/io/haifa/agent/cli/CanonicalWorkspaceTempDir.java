package io.haifa.agent.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/** Uses the canonical host spelling that the model receives in workspace_paths. */
final class CanonicalWorkspaceTempDir implements TempDirFactory {
    @Override
    public Path createTempDirectory(AnnotatedElementContext elementContext, ExtensionContext extensionContext)
            throws IOException {
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        return Files.createTempDirectory(tempRoot, "junit-");
    }
}
