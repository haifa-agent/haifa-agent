package io.haifa.agent.testing.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Strict loader for a versioned suite file in the independent private configuration repository. */
public final class SuiteManifestLoader {
    private final ObjectMapper mapper;

    public SuiteManifestLoader() {
        mapper = new ObjectMapper(new YAMLFactory());
    }

    public SuiteManifest load(Path configRoot, String suiteId) {
        Path root = Objects.requireNonNull(configRoot, "configRoot must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("test config root must be a directory");
        if (suiteId == null || !suiteId.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("suite id must be lowercase kebab-case");
        }
        Path suite = root.resolve("suites").resolve(suiteId + ".yaml").normalize();
        if (!suite.startsWith(root) || !Files.isRegularFile(suite)) {
            throw new IllegalArgumentException("suite file is unavailable: " + suiteId);
        }
        try {
            SuiteManifest manifest = mapper.readValue(suite.toFile(), SuiteManifest.class);
            if (!manifest.suiteId().equals(suiteId)) {
                throw new IllegalArgumentException("suite file id does not match requested suite");
            }
            Path matrix = root.resolve("matrices")
                    .resolve(manifest.matrixRef() + ".yaml")
                    .normalize();
            if (!matrix.startsWith(root) || !Files.isRegularFile(matrix)) {
                throw new IllegalArgumentException("suite matrix is unavailable: " + manifest.matrixRef());
            }
            return manifest;
        } catch (IOException exception) {
            throw new IllegalArgumentException("suite file cannot be parsed: " + suiteId, exception);
        }
    }
}
