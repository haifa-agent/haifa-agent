package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the local runner contract without downloading the authored asset repository. */
class AutonomousDeliveryLadderCaseFilesTest {
    @Test
    void runnerAndExplicitFetcherArePresent() {
        Path moduleRoot = Path.of(System.getProperty("basedir", "."));

        assertTrue(Files.isRegularFile(moduleRoot.resolve("tools/run_case.py")));
        assertTrue(Files.isRegularFile(moduleRoot.resolve("tools/fetch_assets.py")));
        assertTrue(Files.isRegularFile(moduleRoot.resolve("assets.lock.json")));
    }

    @Test
    void ladderResultContractIsPublishedByTheFixturesModule() throws IOException {
        String resource = "fixtures/autonomous-delivery/schemas/acceptance-result.schema.json";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource + " must be on the test classpath");
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(schema.contains("^L[1-6]-0[1-9]$"), "the ladder caseId pattern must be declared");
            assertTrue(schema.contains("INCOMPLETE_BUDGET"), "the budget status must be declared");
            assertTrue(schema.contains("legacyResult"), "the frozen legacy shape must stay accepted");
        }
    }
}
