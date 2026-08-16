package io.haifa.agent.testing.harness;

import java.util.Objects;

/** Safe plan file consumed by the public run action. */
public record ExecutionPlanDocument(
        int schemaVersion,
        String suiteType,
        String projectRoot,
        String configRoot,
        String runRoot,
        String suiteRef,
        String agentProfileRef,
        String platformRef,
        RunMode mode,
        ResolvedTestPlan plan) {
    public ExecutionPlanDocument {
        if (schemaVersion != 1) throw new IllegalArgumentException("execution plan document schemaVersion must be 1");
        suiteType = require(suiteType, "suiteType");
        if (!suiteType.equals("critical-path") && !suiteType.equals("autonomous-delivery")) {
            throw new IllegalArgumentException("unsupported suiteType: " + suiteType);
        }
        projectRoot = require(projectRoot, "projectRoot");
        configRoot = require(configRoot, "configRoot");
        runRoot = require(runRoot, "runRoot");
        suiteRef = require(suiteRef, "suiteRef");
        agentProfileRef = require(agentProfileRef, "agentProfileRef");
        platformRef = require(platformRef, "platformRef");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(plan, "plan must not be null").verifyIntegrity();
    }

    public TestRunRequest request() {
        return new TestRunRequest(
                java.nio.file.Path.of(projectRoot),
                java.nio.file.Path.of(configRoot),
                java.nio.file.Path.of(runRoot),
                suiteRef,
                agentProfileRef,
                platformRef,
                mode,
                null,
                null);
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
