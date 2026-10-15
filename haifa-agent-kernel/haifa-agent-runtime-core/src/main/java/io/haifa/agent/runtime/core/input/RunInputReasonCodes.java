package io.haifa.agent.runtime.core.input;

import io.haifa.agent.core.run.AgentRunStatus;
import java.util.Locale;
import java.util.Objects;

/** Bounded lower-kebab reason codes recorded when steer input is rejected instead of applied. */
public final class RunInputReasonCodes {
    private RunInputReasonCodes() {}

    /** Reason for settling accepted input because the Run reached {@code status}, e.g. {@code run-cancelled}. */
    public static String terminal(AgentRunStatus status) {
        if (!Objects.requireNonNull(status, "status must not be null").isTerminal()) {
            throw new IllegalArgumentException("status must be terminal");
        }
        return "run-" + status.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static String require(String value) {
        String normalized =
                Objects.requireNonNull(value, "reasonCode must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 64 || !normalized.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("reasonCode must be a bounded lower-kebab token");
        }
        return normalized;
    }
}
