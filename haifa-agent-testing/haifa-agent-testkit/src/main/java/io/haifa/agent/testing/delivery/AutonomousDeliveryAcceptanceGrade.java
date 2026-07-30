package io.haifa.agent.testing.delivery;

import java.util.List;
import java.util.Map;

/** Stable, bounded grader projection; raw process output is intentionally excluded. */
public record AutonomousDeliveryAcceptanceGrade(
        String graderId,
        String caseId,
        boolean passed,
        int exitCode,
        Map<String, Boolean> checks,
        List<String> failures,
        long durationMillis) {
    public AutonomousDeliveryAcceptanceGrade {
        if (!"process-json-grader-v1".equals(graderId)) {
            throw new IllegalArgumentException("unsupported grader");
        }
        checks = Map.copyOf(checks);
        failures = List.copyOf(failures);
        if (checks.size() > 64 || failures.size() > 64) {
            throw new IllegalArgumentException("grader result exceeds bounded evidence limits");
        }
        if (passed
                != (exitCode == 0
                        && failures.isEmpty()
                        && checks.values().stream().allMatch(Boolean::booleanValue))) {
            throw new IllegalArgumentException("grader pass state is inconsistent");
        }
    }
}
