package io.haifa.agent.testing.personal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Loads only a named private PA smoke policy; executable case details remain in the public catalog. */
public final class PersonalAssistantSmokeSuiteManifestLoader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public PersonalAssistantSmokeSuiteManifest load(Path configRoot, String suiteId) {
        if (suiteId == null || !suiteId.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("suite id is invalid");
        }
        Path root = Objects.requireNonNull(configRoot, "configRoot must not be null")
                .toAbsolutePath()
                .normalize();
        Path file = root.resolve("suites").resolve(suiteId + ".yaml").normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("suite manifest is unavailable");
        }
        try {
            PersonalAssistantSmokeSuiteManifest manifest =
                    yaml.readValue(file.toFile(), PersonalAssistantSmokeSuiteManifest.class);
            if (!suiteId.equals(manifest.suiteId())) {
                throw new IllegalArgumentException("suite id does not match filename");
            }
            return manifest;
        } catch (IOException exception) {
            throw new IllegalArgumentException("suite manifest cannot be parsed", exception);
        }
    }
}
