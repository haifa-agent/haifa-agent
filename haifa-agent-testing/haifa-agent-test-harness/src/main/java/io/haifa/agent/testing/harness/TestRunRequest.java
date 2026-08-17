package io.haifa.agent.testing.harness;

import java.nio.file.Path;
import java.util.Objects;

/** Provider-neutral request accepted by the shared harness application service. */
public record TestRunRequest(
        Path projectRoot,
        Path configRoot,
        Path runRoot,
        String suiteRef,
        String agentProfileRef,
        String platformRef,
        RunMode mode) {
    public TestRunRequest {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Objects.requireNonNull(configRoot, "configRoot must not be null");
        Objects.requireNonNull(runRoot, "runRoot must not be null");
        suiteRef = require(suiteRef, "suiteRef");
        agentProfileRef = require(agentProfileRef, "agentProfileRef");
        platformRef = require(platformRef, "platformRef");
        Objects.requireNonNull(mode, "mode must not be null");
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
