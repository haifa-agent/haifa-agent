package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutonomousDeliveryCaseCatalogTest {
    @TempDir
    Path temporary;

    @Test
    void loadsAllVerifiedVersionedKnownAndHiddenCases() {
        AutonomousDeliveryCaseCatalog catalog = AutonomousDeliveryCaseCatalog.loadVerified();

        assertEquals("generalized-coding-v1", catalog.catalogId());
        assertEquals(
                java.util.stream.IntStream.rangeClosed(1, 17)
                        .mapToObj(value -> "%02d".formatted(value))
                        .toList(),
                catalog.cases().stream().map(AutonomousDeliveryCase::caseId).toList());
        assertEquals("2.0.0", catalog.require("03").caseVersion());
        assertEquals("2.0.0", catalog.require("04").caseVersion());
        assertTrue(catalog.require("03").optionalChangeNote().isPresent());
        assertEquals("ANALYZE", catalog.require("14").taskType());
        assertThrows(IllegalArgumentException.class, () -> catalog.require("18"));
    }

    @Test
    void materializesOnlyReviewedInputsAndFailsClosedOnExistingDestination() throws Exception {
        AutonomousDeliveryCase testCase =
                AutonomousDeliveryCaseCatalog.loadVerified().require("02");
        Path caseRoot = temporary.resolve("case-02");

        new AutonomousDeliveryFixtureStore().materializeCase(testCase, caseRoot);

        assertTrue(Files.isRegularFile(caseRoot.resolve("prompt.txt")));
        assertTrue(Files.isRegularFile(caseRoot.resolve("acceptance.py")));
        assertTrue(Files.isDirectory(caseRoot.resolve("base-workspace")));
        assertFalse(Files.exists(caseRoot.resolve("runtime.db")));
        assertFalse(Files.exists(caseRoot.resolve(".git")));
        assertTrue(Files.isExecutable(caseRoot.resolve("base-workspace/task-board")));
        assertTrue(Files.isExecutable(caseRoot.resolve("base-workspace/test.sh")));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> new AutonomousDeliveryFixtureStore()
                .materializeCase(testCase, caseRoot));
    }
}
