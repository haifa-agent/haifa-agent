package io.haifa.agent.core;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCategory;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.error.AgentErrorSeverity;
import io.haifa.agent.core.error.Retryability;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunSpec;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Map;

final class CoreTestFixtures {

    static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    private CoreTestFixtures() {}

    static AgentRunSpec runSpec(int maxDepth) {
        return new AgentRunSpec(
                new AgentSessionId("session-1"),
                new ProjectRef("project-1"),
                new TenantRef("local"),
                new PrincipalRef("local-user", "user"),
                new AgentDefinitionId("coding-agent"),
                new AgentDefinitionVersion(1, 2, 0),
                "coding-product",
                "2.1.0",
                AgentRunType.CODING,
                "Inspect and improve the project",
                new AgentRunBudget(1000, 1000, 1000, 10, 10, 5, "USD", 1000),
                new AgentRunLimits(20, maxDepth, 4, 60_000, 10_000),
                new RunConfigurationSnapshotRef("snapshot-1", "sha256:abc"));
    }

    static AgentError error(Instant at) {
        return new AgentError(
                new AgentErrorCode("MODEL_RATE_LIMITED"),
                AgentErrorCategory.MODEL,
                AgentErrorSeverity.ERROR,
                Retryability.RETRYABLE_WITH_BACKOFF,
                "The model endpoint rejected the request",
                "trace:error-1",
                Map.of("providerCode", "429"),
                at);
    }
}
