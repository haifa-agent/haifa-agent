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
    }
}
