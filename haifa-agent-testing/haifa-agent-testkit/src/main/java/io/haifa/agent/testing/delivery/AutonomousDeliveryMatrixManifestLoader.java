package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Strict loader for an Autonomous Delivery matrix in the independent test-config repository. */
public final class AutonomousDeliveryMatrixManifestLoader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public AutonomousDeliveryMatrixManifest load(Path configRoot, String matrixId) {
        Path root = Objects.requireNonNull(configRoot, "configRoot must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("test config root must be a directory");
        }
        if (matrixId == null || !matrixId.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("matrix id must be lowercase kebab-case");
        }
        Path matrix = root.resolve("matrices").resolve(matrixId + ".yaml").normalize();
        if (!matrix.startsWith(root) || !Files.isRegularFile(matrix)) {
            throw new IllegalArgumentException("Autonomous Delivery matrix is unavailable: " + matrixId);
        }
        try {
            AutonomousDeliveryMatrixManifest manifest =
                    yaml.readValue(matrix.toFile(), AutonomousDeliveryMatrixManifest.class);
            if (!manifest.matrixId().equals(matrixId)) {
                throw new IllegalArgumentException("matrix file id does not match requested matrix");
            }
            return manifest;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Autonomous Delivery matrix cannot be parsed: " + matrixId, exception);
        }
    }
}
