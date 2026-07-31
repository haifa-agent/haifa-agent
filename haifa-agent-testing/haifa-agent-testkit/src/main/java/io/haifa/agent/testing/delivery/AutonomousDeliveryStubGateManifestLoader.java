package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads the private, no-Case Stub Gate policy from the same reviewed Suite directory. */
public final class AutonomousDeliveryStubGateManifestLoader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public AutonomousDeliveryStubGateManifest load(Path configRoot, String suiteId) throws IOException {
        if (suiteId == null || !suiteId.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Stub Gate suite id is invalid");
        }
        Path root = configRoot.toAbsolutePath().normalize();
        Path file = root.resolve("suites").resolve(suiteId + ".yaml").normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Stub Gate suite manifest is unavailable");
        }
        AutonomousDeliveryStubGateManifest manifest =
                yaml.readValue(file.toFile(), AutonomousDeliveryStubGateManifest.class);
        if (!suiteId.equals(manifest.suiteId())) {
            throw new IllegalArgumentException("Stub Gate suite id does not match filename");
        }
        return manifest;
    }
}
