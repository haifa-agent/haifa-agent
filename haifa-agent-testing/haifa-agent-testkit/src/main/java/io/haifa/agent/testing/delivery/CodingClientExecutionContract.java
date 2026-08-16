package io.haifa.agent.testing.delivery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Safe, provider-neutral evidence that one capability case ran through the standard product client. */
record CodingClientExecutionContract(
        boolean assemblyOpened,
        boolean runStarted,
        boolean terminalStateObserved,
        boolean completedWithinBudget,
        boolean assemblyClosed,
        String terminalStatus,
        int publicEventCount,
        String profileAssemblyDigest,
        String productAssemblyDigest,
        List<String> failureCodes) {

    CodingClientExecutionContract {
        terminalStatus = requireText(terminalStatus, "terminalStatus");
        if (publicEventCount < 0) throw new IllegalArgumentException("publicEventCount must not be negative");
        profileAssemblyDigest = requireDigest(profileAssemblyDigest, "profileAssemblyDigest");
        productAssemblyDigest = requireDigest(productAssemblyDigest, "productAssemblyDigest");
        failureCodes = List.copyOf(Objects.requireNonNull(failureCodes, "failureCodes must not be null"));
        if (failureCodes.size() > 16
                || failureCodes.stream().anyMatch(code -> code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}"))) {
            throw new IllegalArgumentException("failureCodes contains an invalid safe code");
        }
    }

    boolean passed() {
        return assemblyOpened
                && runStarted
                && terminalStateObserved
                && completedWithinBudget
                && assemblyClosed
                && publicEventCount > 0
                && failureCodes.isEmpty();
    }

    Map<String, Object> artifact() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("assemblyOpened", assemblyOpened);
        result.put("runStarted", runStarted);
        result.put("terminalStateObserved", terminalStateObserved);
        result.put("completedWithinBudget", completedWithinBudget);
        result.put("assemblyClosed", assemblyClosed);
        result.put("terminalStatus", terminalStatus);
        result.put("publicEventCount", publicEventCount);
        result.put("profileAssemblyDigest", profileAssemblyDigest);
        result.put("productAssemblyDigest", productAssemblyDigest);
        result.put("failureCodes", failureCodes);
        result.put("passed", passed());
        return Map.copyOf(result);
    }

    private static String requireDigest(String value, String field) {
        String normalized = requireText(value, field);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException(field + " must contain 1..128 characters");
        }
        return normalized;
    }
}
