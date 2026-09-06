package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;
import static io.haifa.agent.policy.api.PolicyValues.requireSafeText;

public record ApprovalTargetRef(
        String targetType,
        String targetId,
        String targetVersion,
        String operation,
        String targetDigest,
        String safeSummary) {
    public ApprovalTargetRef {
        targetType = requireIdentifier(targetType, "targetType");
        targetId = requireIdentifier(targetId, "targetId");
        targetVersion = requireIdentifier(targetVersion, "targetVersion");
        operation = requireIdentifier(operation, "operation");
        targetDigest = requireIdentifier(targetDigest, "targetDigest");
        safeSummary = requireSafeText(safeSummary, "safeSummary");
        if (hostAbsolutePath(targetId)) {
            throw new IllegalArgumentException("targetId must be opaque and must not contain a host absolute path");
        }
    }

    private static boolean hostAbsolutePath(String value) {
        return value.startsWith("/")
                || value.startsWith("\\\\")
                || value.startsWith("//")
                || value.regionMatches(true, 0, "file:", 0, 5)
                || (value.length() >= 3
                        && Character.isLetter(value.charAt(0))
                        && value.charAt(1) == ':'
                        && (value.charAt(2) == '\\' || value.charAt(2) == '/'));
    }
}
