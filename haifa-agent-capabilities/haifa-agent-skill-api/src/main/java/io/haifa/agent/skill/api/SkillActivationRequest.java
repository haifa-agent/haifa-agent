package io.haifa.agent.skill.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;

public record SkillActivationRequest(
        AgentRunId runId,
        TenantRef tenant,
        PrincipalRef principal,
        SkillAlias alias,
        String reason,
        String requestedBy) {
    public SkillActivationRequest {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        alias = Objects.requireNonNull(alias, "alias must not be null");
        reason = SkillValues.text(reason, "reason", 512);
        requestedBy = SkillValues.text(requestedBy, "requestedBy", 128);
    }
}
