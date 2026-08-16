package io.haifa.agent.testing.harness;

import java.util.Locale;

/** Governance strictness applied by the shared test harness lifecycle. */
public enum RunMode {
    DEV,
    LIVE,
    RELEASE;

    public static RunMode parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("run mode is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("run mode must be dev, live, or release", exception);
        }
    }

    public boolean atLeast(RunMode minimum) {
        return ordinal() >= minimum.ordinal();
    }

    public boolean requiresBudgetApproval() {
        return this != DEV;
    }

    public boolean requiresExternalRunRoot() {
        return this != DEV;
    }

    public boolean requiresPlanApproval() {
        return this != DEV;
    }

    public boolean requiresFullAssetInventory() {
        return this == RELEASE;
    }

    public boolean requiresReadOnlyEvidence() {
        return this == RELEASE;
    }
}
