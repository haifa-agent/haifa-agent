package io.haifa.agent.testing.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalAssistantSmokeSuiteManifestLoaderTest {
    @TempDir Path temporaryDirectory;

    @Test
    void loadsOnlyThePersonalAssistantSmokeSuiteTypeAndPublicCaseIds() throws Exception {
        Path suites = Files.createDirectories(temporaryDirectory.resolve("suites"));
        Files.writeString(
                suites.resolve("personal-assistant-smoke-v1.yaml"),
                """
                schemaVersion: 1
                suiteType: personal-assistant-smoke
                suiteId: personal-assistant-smoke-v1
                matrixRef: primary-v1
                budget:
                  maxWallTimeMinutes: 15
                  maxParallelExternalCalls: 1
                cases:
                  - caseId: PA-SM-01
                    repetitions: 1
                    blocking: true
                """);

        PersonalAssistantSmokeSuiteManifest manifest = new PersonalAssistantSmokeSuiteManifestLoader()
                .load(temporaryDirectory, "personal-assistant-smoke-v1");

        assertEquals("personal-assistant-smoke", manifest.suiteType());
        assertEquals("PA-SM-01", manifest.cases().getFirst().caseId());
    }
}
