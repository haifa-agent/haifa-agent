package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutonomousDeliveryPhaseThreeVerificationCollectorTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void writesBoundedVerificationAndAtomicityEvidenceForHighRiskCase() throws Exception {
        AutonomousDeliveryPhaseThreeVerificationCollector collector =
                new AutonomousDeliveryPhaseThreeVerificationCollector(json);

        AutonomousDeliveryPhaseThreeVerificationCollector.Result result = collector.collect(
                temp,
                input(
                        metadata(List.of("FAILURE_ATOMICITY", "RESOURCE_CLEANUP")),
                        true,
                        Map.of("z-last", true, "a-first", true),
                        List.of(),
                        0,
                        true));

        assertTrue(result.passed());
        assertEquals("PASS", result.atomicity());
        JsonNode plan = json.readTree(temp.resolve("verification-plan.json").toFile());
        assertEquals(5, plan.path("dimensions").size());
        assertEquals("HIGH", plan.path("riskLevel").asText());
        assertFalse(plan.path("containsExecutableCode").asBoolean());
        JsonNode verification =
                json.readTree(temp.resolve("verification-evidence.json").toFile());
        assertTrue(verification.path("passed").asBoolean());
        assertEquals(
                plan.path("digest").asText(), verification.path("planDigest").asText());
        assertEquals(
                "acceptance-result.json#checks/a-first",
                verification.path("evidence").path(0).path("sourceRef").asText());
        JsonNode sideEffect =
                json.readTree(temp.resolve("side-effect-evidence.json").toFile());
        assertTrue(sideEffect.path("atomicityRequired").asBoolean());
        assertEquals(
                "PROCESS_AND_SCRATCH_CLEAN", sideEffect.path("cleanupEvidence").asText());
        assertTrue(Files.isRegularFile(temp.resolve("capability-matrix.json")));
    }

    @Test
    void emptyAcceptanceChecksFailClosedInsteadOfProducingSyntheticEvidence() throws Exception {
        AutonomousDeliveryPhaseThreeVerificationCollector collector =
                new AutonomousDeliveryPhaseThreeVerificationCollector(json);

        AutonomousDeliveryPhaseThreeVerificationCollector.Result result =
                collector.collect(temp, input(metadata(List.of()), true, Map.of(), List.of(), 0, true));

        assertFalse(result.passed());
        assertEquals("NOT_APPLICABLE", result.atomicity());
        JsonNode verification =
                json.readTree(temp.resolve("verification-evidence.json").toFile());
        assertFalse(verification.path("passed").asBoolean());
        assertEquals(0, verification.path("evidence").size());
    }

    @Test
    void highRiskCaseFailsAtomicityWhenCleanupIsNotConfirmed() throws Exception {
        AutonomousDeliveryPhaseThreeVerificationCollector collector =
                new AutonomousDeliveryPhaseThreeVerificationCollector(json);

        AutonomousDeliveryPhaseThreeVerificationCollector.Result result = collector.collect(
                temp,
                input(
                        metadata(List.of("SECURITY")),
                        false,
                        Map.of("hidden-check", false),
                        List.of("hidden-check"),
                        1,
                        false));

        assertFalse(result.passed());
        assertEquals("FAIL", result.atomicity());
        JsonNode sideEffect =
                json.readTree(temp.resolve("side-effect-evidence.json").toFile());
        assertEquals("REJECTED", sideEffect.path("operationResult").asText());
        assertEquals("CLEANUP_NOT_CONFIRMED", sideEffect.path("cleanupEvidence").asText());
        assertEquals(
                "hidden-check", sideEffect.path("unexpectedChanges").path(0).asText());
    }

    private static AutonomousDeliveryPhaseThreeVerificationCollector.Input input(
            AutonomousDeliveryRepeatEvidenceCollector.CaseMetadata metadata,
            boolean acceptancePassed,
            Map<String, Boolean> checks,
            List<String> failures,
            long scratchCleanupFailures,
            boolean processFinished) {
        return new AutonomousDeliveryPhaseThreeVerificationCollector.Input(
                metadata,
                acceptancePassed,
                checks,
                failures,
                "before",
                "after",
                scratchCleanupFailures,
                processFinished);
    }

    private static AutonomousDeliveryRepeatEvidenceCollector.CaseMetadata metadata(List<String> risks) {
        return new AutonomousDeliveryRepeatEvidenceCollector.CaseMetadata(
                "17", "2.0.0", "java", "repair", List.of("EDIT", "TEST"), risks);
    }
}
