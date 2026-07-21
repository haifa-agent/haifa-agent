package io.haifa.agent.core.run;

import static io.haifa.agent.core.support.DomainValues.requireText;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;
import java.util.Optional;

/** Immutable input needed to create a reproducible logical run. */
public record AgentRunSpec(
        AgentSessionId sessionId,
        ProjectRef project,
        TenantRef tenant,
        PrincipalRef principal,
        AgentDefinitionId agentDefinitionId,
        AgentDefinitionVersion agentDefinitionVersion,
        String productProfileId,
        String productProfileVersion,
        AgentRunType runType,
        String objective,
        AgentRunBudget budget,
        AgentRunLimits limits,
        RunConfigurationSnapshotRef configurationSnapshot) {

    public AgentRunSpec {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        agentDefinitionId = Objects.requireNonNull(agentDefinitionId, "agentDefinitionId must not be null");
        agentDefinitionVersion =
                Objects.requireNonNull(agentDefinitionVersion, "agentDefinitionVersion must not be null");
        productProfileId = requireText(productProfileId, "productProfileId");
        productProfileVersion = requireText(productProfileVersion, "productProfileVersion");
        runType = Objects.requireNonNull(runType, "runType must not be null");
        objective = requireText(objective, "objective");
        budget = Objects.requireNonNull(budget, "budget must not be null");
        limits = Objects.requireNonNull(limits, "limits must not be null");
        configurationSnapshot = Objects.requireNonNull(configurationSnapshot, "configurationSnapshot must not be null");
    }

    public Optional<ProjectRef> optionalProject() {
        return Optional.ofNullable(project);
    }
}
