package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutonomousDeliveryCaseCatalogTest {
    @TempDir
    Path temporary;

    @Test
    void loadsExactlyTenVerifiedVersionedCases() {
        AutonomousDeliveryCaseCatalog catalog = AutonomousDeliveryCaseCatalog.loadVerified();

        assertEquals("generalized-coding-v1", catalog.catalogId());
        assertEquals(
                List.of("01", "02", "03", "04", "05", "06", "07", "08", "09", "10"),
                catalog.cases().stream().map(AutonomousDeliveryCase::caseId).toList());
        assertEquals("2.0.0", catalog.require("03").caseVersion());
        assertEquals("2.0.0", catalog.require("04").caseVersion());
        assertTrue(catalog.require("03").optionalChangeNote().isPresent());
        assertThrows(IllegalArgumentException.class, () -> catalog.require("11"));
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
        assertTrue(Files.isExecutable(caseRoot.resolve("base-workspace/test.sh")));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> new AutonomousDeliveryFixtureStore()
                .materializeCase(testCase, caseRoot));
    }
}
