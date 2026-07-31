package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.haifa.agent.testing.suite.MavenTestEvidence;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutonomousDeliveryDeterministicProbeExecutorTest {
    @TempDir
    Path temp;

    @Test
    void freezesProviderFreeProbeCommands() {
        Path wrapper = temp.resolve("mvnw.cmd");
        Path reports = temp.resolve("reports");

        assertEquals(
                List.of(
                        wrapper.toString(),
                        "--batch-mode",
                        "--no-transfer-progress",
                        "-pl",
                        ":haifa-agent-cli",
                        "-am",
                        "-Dtest=LocalCodingAgentTest#stubAnalyzeRunUsesReadOnlyEvidenceWithoutRequiringWorkspaceChange",
                        "-Dsurefire.failIfNoSpecifiedTests=false",
                        "-Dsurefire.reportsDirectory=" + reports,
                        "test"),
                AutonomousDeliveryDeterministicProbeExecutor.command(
                        wrapper,
                        AutonomousDeliveryDeterministicProbeExecutor.ProbeDefinition.READ_ONLY_ANALYZE,
                        reports));
        assertEquals(
                ":haifa-agent-runtime-core",
                AutonomousDeliveryDeterministicProbeExecutor.command(
                                wrapper,
                                AutonomousDeliveryDeterministicProbeExecutor.ProbeDefinition.TRACE_REPLAY,
                                reports)
                        .get(4));
        assertTrue(AutonomousDeliveryDeterministicProbeExecutor.command(
                        wrapper, AutonomousDeliveryDeterministicProbeExecutor.ProbeDefinition.TRACE_REPLAY, reports)
                .contains("-Dtest=RuntimeControlTraceReplayTest"));
    }

    @Test
    void removesCredentialsAndLiveOptInsButPreservesUnrelatedEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATH", "toolchain");
        environment.put("DEEPSEEK_API_KEY", "secret");
        environment.put("ALIYUN_IQS_API_KEY", "secret");
        environment.put("HAIFA_CONTINUATION_KEY", "secret");
        environment.put("HAIFA_DEEPSEEK_LIVE_TEST", "true");
        environment.put("HAIFA_CLI_LIVE_E2E_TEST", "true");
        environment.put("HAIFA_CLI_LIVE_E2E_PROVIDER", "deepseek");

        AutonomousDeliveryDeterministicProbeExecutor.isolateFromProviderSecrets(environment);

        assertEquals(Map.of("PATH", "toolchain"), environment);
    }

    @Test
    void derivesStatusFromRawMavenEvidenceAndDeletesTheRawXml() throws Exception {
        Path reports = temp.resolve("passing-reports");
        Files.createDirectories(reports);
        Files.writeString(
                reports.resolve("TEST-probe.xml"),
                "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"/>",
                StandardCharsets.UTF_8);

        Map<String, Object> result =
                AutonomousDeliveryDeterministicProbeExecutor.deterministicTestEvidence(reports, true, 0);

        assertEquals(MavenTestEvidence.Status.PASSED, result.get("status"));
        assertEquals(true, result.get("passed"));
        MavenTestEvidence evidence = (MavenTestEvidence) result.get("testEvidence");
        assertEquals(1, evidence.reportFiles());
        assertEquals(1, evidence.tests());
        assertFalse(Files.exists(reports));
    }

    @Test
    void reportsTimeoutEvenWhenNoRawEvidenceExists() {
        Path reports = temp.resolve("missing-reports");

        Map<String, Object> result =
                AutonomousDeliveryDeterministicProbeExecutor.deterministicTestEvidence(reports, false, 124);

        assertEquals(MavenTestEvidence.Status.TIMEOUT, result.get("status"));
        assertEquals(false, result.get("passed"));
        assertFalse(Files.exists(reports));
    }
}
