package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryCapabilityLadderTest {
    @Test
    void capabilityLadderSpecAndDocumentationArePresent() {
        Path moduleRoot = Path.of(System.getProperty("basedir", "."));
        Path specFile = moduleRoot.resolve("AUTONOMOUS_DELIVERY_LADDER_SPEC.md");
        Path readmeFile = moduleRoot.resolve("README.md");

        assertTrue(Files.isRegularFile(specFile), "AUTONOMOUS_DELIVERY_LADDER_SPEC.md must exist in module root");
        assertTrue(Files.isRegularFile(readmeFile), "README.md must exist in module root");
    }

    @Test
    void fixtureStoreAndCaseCatalogRemainAccessible() {
        AutonomousDeliveryFixtureStore store = new AutonomousDeliveryFixtureStore();
        AutonomousDeliveryCaseCatalog catalog = AutonomousDeliveryCaseCatalog.loadVerified(store);

        assertFalse(catalog.cases().isEmpty(), "Historical case catalog must be loadable from fixtures");
        assertNotNull(catalog.require("01"), "Case 01 must exist in catalog");
    }

    @Test
    void ladderCatalogMatchesPlannedDistribution() {
        List<AutonomousDeliveryLadderCase> cases = AutonomousDeliveryLadderCatalog.cases();

        assertEquals(23, cases.size(), "ladder must define exactly 23 planned cases");
        AutonomousDeliveryLadderCatalog.byLevel()
                .forEach((level, levelCases) -> assertEquals(
                        level.plannedCases(), levelCases.size(), level + " case count must match the plan"));
        long baselineCases = cases.stream()
                .filter(ladderCase -> ladderCase.level().ordinal() <= LadderLevel.L4.ordinal())
                .count();
        assertTrue(baselineCases >= Math.ceil(cases.size() * 0.7), "at least 70% of the cases must sit in L1..L4");
    }

    @Test
    void everyLadderCaseCarriesThreeDimensionalLabels() {
        for (AutonomousDeliveryLadderCase ladderCase : AutonomousDeliveryLadderCatalog.cases()) {
            assertTrue(ladderCase.localizationComplexity() >= 1 && ladderCase.localizationComplexity() <= 5);
            assertTrue(ladderCase.modificationSpan() >= 1 && ladderCase.modificationSpan() <= 5);
            assertTrue(ladderCase.acceptanceComplexity() >= 1 && ladderCase.acceptanceComplexity() <= 5);
            assertFalse(ladderCase.isPlaceholder(), ladderCase.caseId() + " must define its task statement");
        }
    }

    @Test
    void orthogonalVariantsMarkThePlannedCases() {
        assertEquals(
                List.of(LadderVariant.ERROR_RECOVERY),
                AutonomousDeliveryLadderCatalog.require("L1-03").variants());
        assertEquals(
                List.of(LadderVariant.ERROR_RECOVERY),
                AutonomousDeliveryLadderCatalog.require("L3-02").variants());
        assertEquals(
                List.of(LadderVariant.REGRESSION_PROTECTION),
                AutonomousDeliveryLadderCatalog.require("L2-05").variants());
        assertEquals(
                List.of(LadderVariant.REGRESSION_PROTECTION),
                AutonomousDeliveryLadderCatalog.require("L4-04").variants());
    }
}
