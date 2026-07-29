package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Loads private suite policy without copying public catalog or grader implementation details. */
public final class AutonomousDeliverySuiteManifestLoader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public AutonomousDeliverySuiteManifest load(Path configRoot, String suiteId, AutonomousDeliveryCaseCatalog catalog)
            throws IOException {
        if (suiteId == null || !suiteId.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("suite id is invalid");
        }
        Path root = configRoot.toAbsolutePath().normalize();
        Path file = root.resolve("suites").resolve(suiteId + ".yaml").normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("suite manifest is unavailable");
        }
        AutonomousDeliverySuiteManifest manifest = yaml.readValue(file.toFile(), AutonomousDeliverySuiteManifest.class);
        if (!suiteId.equals(manifest.suiteId())) {
            throw new IllegalArgumentException("suite id does not match filename");
        }
        Set<String> selected = new HashSet<>();
        for (AutonomousDeliverySuiteManifest.CaseSelection selection : manifest.cases()) {
            catalog.require(selection.caseId());
            if (!selected.add(selection.caseId())) {
                throw new IllegalArgumentException("suite must not contain duplicate case ids");
            }
        }
        return manifest;
    }
}
