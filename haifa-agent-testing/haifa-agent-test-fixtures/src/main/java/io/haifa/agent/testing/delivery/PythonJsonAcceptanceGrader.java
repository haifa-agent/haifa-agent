package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Executes a reviewed Python acceptance oracle outside the candidate workspace. */
public final class PythonJsonAcceptanceGrader {
    private static final long MAX_OUTPUT_BYTES = 1_048_576;
    private final ObjectMapper json = new ObjectMapper();

    public AutonomousDeliveryAcceptanceGrade grade(
            AutonomousDeliveryCase testCase,
            Path acceptanceScript,
            Path candidateWorkspace,
            Path pythonExecutable,
            Duration timeout,
            String toolchainPath)
            throws IOException, InterruptedException {
        Path acceptance = requireFile(acceptanceScript, "acceptance script");
        Path workspace = requireDirectory(candidateWorkspace, "candidate workspace");
        Path python = requireFile(pythonExecutable, "python executable");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("acceptance timeout must be in (0, 15 minutes]");
        }
        Path output = Files.createTempFile("haifa-acceptance-", ".stdout");
        Path error = Files.createTempFile("haifa-acceptance-", ".stderr");
        Instant started = Instant.now();
        try {
            ProcessBuilder builder = new ProcessBuilder(oracleCommand(python, acceptance, workspace))
                    .directory(acceptance.getParent().toFile())
                    .redirectOutput(output.toFile())
                    .redirectError(error.toFile());
            configureOracleEnvironment(builder.environment(), toolchainPath);
            Process process = builder.start();
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                throw new IOException("acceptance oracle exceeded its timeout");
            }
            if (Files.size(output) > MAX_OUTPUT_BYTES || Files.size(error) > MAX_OUTPUT_BYTES) {
                throw new IOException("acceptance oracle output exceeded its bound");
            }
            int exitCode = process.exitValue();
            return parse(
                    testCase,
                    Files.readAllBytes(output),
                    exitCode,
                    Duration.between(started, Instant.now()).toMillis());
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(error);
        }
    }

    static void configureOracleEnvironment(Map<String, String> environment, String toolchainPath) {
        if (toolchainPath == null || toolchainPath.isBlank()) {
            throw new IllegalArgumentException("toolchain path must not be blank");
        }
        environment.put("PATH", toolchainPath);
    }

    static List<String> oracleCommand(Path python, Path acceptance, Path workspace) {
        return List.of(python.toString(), "-X", "utf8", "-I", acceptance.toString(), workspace.toString());
    }

    AutonomousDeliveryAcceptanceGrade parse(
            AutonomousDeliveryCase testCase, byte[] output, int exitCode, long durationMillis) throws IOException {
        if (output.length > MAX_OUTPUT_BYTES) {
            throw new IOException("acceptance oracle output exceeded its bound");
        }
        OraclePayload payload = json.readValue(output, OraclePayload.class);
        List<String> failures = payload.failures() == null ? List.of() : payload.failures();
        Map<String, Boolean> checks = payload.checks() == null ? Map.of() : payload.checks();
        boolean passed = payload.passed() && exitCode == 0;
        if (!passed && failures.isEmpty()) {
            failures = List.of("ORACLE_REPORTED_FAILURE");
        }
        return new AutonomousDeliveryAcceptanceGrade(
                testCase.graderId(),
                testCase.caseId(),
                passed,
                exitCode,
                new LinkedHashMap<>(checks),
                failures,
                durationMillis);
    }

    private static Path requireFile(Path value, String field) {
        Path normalized = value.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException(field + " must be a regular file");
        }
        return normalized;
    }

    private static Path requireDirectory(Path value, String field) {
        Path normalized = value.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException(field + " must be a directory");
        }
        return normalized;
    }

    private record OraclePayload(
            @JsonProperty("case") String caseName,
            boolean passed,
            Map<String, Boolean> checks,
            List<String> failures) {}
}
