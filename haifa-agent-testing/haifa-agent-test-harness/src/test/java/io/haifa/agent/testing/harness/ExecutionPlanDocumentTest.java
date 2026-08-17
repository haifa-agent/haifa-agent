package io.haifa.agent.testing.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionPlanDocumentTest {
    private static final RunnerArtifact RUNNER =
            new RunnerArtifact(1, "runner.jar", "a".repeat(64), RunnerArtifact.MAIN_CLASS);

    @TempDir
    Path temporaryDirectory;

    @Test
    void serializesOneCanonicalRequestAndRunnerBoundPlan() throws Exception {
        TestRunRequest request = new TestRunRequest(
                temporaryDirectory.resolve("product"),
                temporaryDirectory.resolve("config"),
                temporaryDirectory.resolve("runs"),
                "suite-v1",
                "profile-v1",
                "windows-primary",
                RunMode.LIVE);
        ResolvedTestPlan nativePlan = ResolvedTestPlan.freeze(Map.of("suiteType", "critical-path"));
        ExecutionPlanDocument document = ExecutionPlanDocument.freeze(request, nativePlan, RUNNER);

        String json = new ObjectMapper().writeValueAsString(document);
        ExecutionPlanDocument restored = new ObjectMapper().readValue(json, ExecutionPlanDocument.class);

        assertEquals("critical-path", restored.suiteType());
        assertEquals(RUNNER, restored.runnerArtifact());
        assertEquals(
                ResolvedTestPlan.freeze(Map.of("suiteType", "critical-path")).sha256(),
                restored.nativePlan().sha256());
        assertEquals(request, restored.toRunRequest());
        assertEquals(1, count(json, "projectRoot"));
        assertEquals(1, count(json, "suiteRef"));
        assertEquals(1, count(json, "platformRef"));
    }

    @Test
    void rejectsOldSchemaAndRelativeCoordinates() {
        ResolvedTestPlan plan = ExecutionPlanDocument.freeze(
                        new TestRunRequest(
                                temporaryDirectory,
                                temporaryDirectory,
                                temporaryDirectory,
                                "suite",
                                "profile",
                                "platform",
                                RunMode.DEV),
                        ResolvedTestPlan.freeze(Map.of("suiteType", "critical-path")),
                        RUNNER)
                .plan();

        assertThrows(IllegalArgumentException.class, () -> new ExecutionPlanDocument(1, plan));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionPlanDocument.RequestCoordinates(
                        "relative",
                        temporaryDirectory.toString(),
                        temporaryDirectory.toString(),
                        "suite",
                        "profile",
                        "platform",
                        RunMode.DEV));
    }

    private static int count(String value, String token) {
        return value.split(token, -1).length - 1;
    }
}
