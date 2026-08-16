package io.haifa.agent.testing.delivery;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Private deterministic platform Gate policy; it never selects or scores Coding Cases. */
public record AutonomousDeliveryStubGateManifest(
        int schemaVersion,
        String suiteId,
        String gateType,
        String dependencyMode,
        String platform,
        String matrixRef,
        Budget budget,
        List<String> requiredChecks) {
    static final Set<String> REQUIRED_CHECKS = Set.of(
            "CONPTY",
            "SHADED_JAR",
            "ARGUMENTS",
            "YAML",
            "STDIO",
            "EXIT_CODE",
            "APPROVAL",
            "SHELL",
            "SQLITE",
            "SECRET_SCAN",
            "EVIDENCE",
            "PROCESS_TREE",
            "WORKSPACE_CLEANUP",
            "REPOSITORY_STABILITY",
            "NO_EXTERNAL_PROVIDER");

    public AutonomousDeliveryStubGateManifest {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported Autonomous Delivery Stub Gate schema");
        }
        requireIdentifier(suiteId, "suiteId");
        if (!"PLATFORM_STUB".equals(gateType)) {
            throw new IllegalArgumentException("Stub Gate gateType must be PLATFORM_STUB");
        }
        if (!"STUB".equals(dependencyMode)) {
            throw new IllegalArgumentException("Stub Gate dependencyMode must be STUB");
        }
        if (!"windows".equals(platform)) {
            throw new IllegalArgumentException("first Stub Gate platform must be windows");
        }
        requireIdentifier(matrixRef, "matrixRef");
        Objects.requireNonNull(budget, "budget must not be null");
        requiredChecks = List.copyOf(Objects.requireNonNull(requiredChecks, "requiredChecks must not be null"));
        if (requiredChecks.size() != REQUIRED_CHECKS.size()
                || !Set.copyOf(requiredChecks).equals(REQUIRED_CHECKS)) {
            throw new IllegalArgumentException("Stub Gate requiredChecks must match the reviewed platform contract");
        }
    }

    public record Budget(long maxWallTimeMillis, int maxParallelExternalCalls, double maxEstimatedCostUsd) {
        public Budget {
            if (maxWallTimeMillis < 30_000 || maxWallTimeMillis > 900_000) {
                throw new IllegalArgumentException("Stub Gate maxWallTimeMillis must be in [30000, 900000]");
            }
            if (maxParallelExternalCalls != 0 || Double.compare(maxEstimatedCostUsd, 0.0) != 0) {
                throw new IllegalArgumentException("Stub Gate must prohibit external calls and cost");
            }
        }
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(field + " must be lowercase kebab-case");
        }
    }
}
