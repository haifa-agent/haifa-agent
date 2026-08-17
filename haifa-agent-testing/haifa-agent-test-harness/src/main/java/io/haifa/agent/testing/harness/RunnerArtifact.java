package io.haifa.agent.testing.harness;

import io.haifa.agent.testing.evidence.Sha256Digests;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Safe, content-addressed identity of the exact shaded runner approved by a plan. */
public record RunnerArtifact(int schemaVersion, String artifactName, String sha256, String mainClass) {
    static final String MAIN_CLASS = "io.haifa.agent.testing.harness.TestHarnessMain";

    public RunnerArtifact {
        if (schemaVersion != 1) throw new IllegalArgumentException("runner artifact schemaVersion must be 1");
        artifactName = require(artifactName, "artifactName");
        if (!Path.of(artifactName).getFileName().toString().equals(artifactName)) {
            throw new IllegalArgumentException("runner artifactName must not contain a path");
        }
        if (!artifactName.endsWith(".jar")) throw new IllegalArgumentException("runner artifactName must be a JAR");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("runner sha256 must be lowercase SHA-256");
        }
        mainClass = require(mainClass, "mainClass");
        if (!MAIN_CLASS.equals(mainClass)) throw new IllegalArgumentException("runner mainClass is unsupported");
    }

    static RunnerArtifact current() {
        CodeSource source = TestHarnessMain.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalArgumentException("runner code source is unavailable");
        }
        Path artifact;
        try {
            artifact = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("runner code source is invalid", exception);
        }
        return fromPath(artifact);
    }

    static RunnerArtifact fromPath(Path artifact) {
        artifact = Objects.requireNonNull(artifact, "runner artifact path must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalArgumentException("test harness plan and run require a packaged runner JAR");
        }
        try {
            return new RunnerArtifact(1, artifact.getFileName().toString(), Sha256Digests.file(artifact), MAIN_CLASS);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("runner JAR could not be hashed", exception);
        }
    }

    static RunnerArtifact fromReviewedInput(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("resolved plan must contain runnerArtifact");
        }
        Object version = map.get("schemaVersion");
        if (!(version instanceof Number number)) {
            throw new IllegalArgumentException("runner artifact schemaVersion must be numeric");
        }
        if (number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException("runner artifact schemaVersion must be an integer");
        }
        return new RunnerArtifact(
                number.intValue(), string(map, "artifactName"), string(map, "sha256"), string(map, "mainClass"));
    }

    Map<String, Object> reviewedInput() {
        LinkedHashMap<String, Object> input = new LinkedHashMap<>();
        input.put("schemaVersion", schemaVersion);
        input.put("artifactName", artifactName);
        input.put("sha256", sha256);
        input.put("mainClass", mainClass);
        return Map.copyOf(input);
    }

    void requireCurrent(RunnerArtifact current) {
        if (!equals(Objects.requireNonNull(current, "current runner artifact must not be null"))) {
            throw new IllegalArgumentException("approved plan does not match the current runner JAR");
        }
    }

    private static String string(Map<?, ?> map, String field) {
        Object value = map.get(field);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("runner artifact " + field + " is invalid");
        }
        return text;
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
