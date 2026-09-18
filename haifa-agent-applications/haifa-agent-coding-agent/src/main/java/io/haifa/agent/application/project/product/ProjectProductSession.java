package io.haifa.agent.application.project.product;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.configuration.ProjectConfigurationVersion;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

public record ProjectProductSession(
        AgentSessionId sessionId,
        ProjectId projectId,
        WorkspaceId workspaceId,
        TenantRef tenant,
        PrincipalRef principal,
        ProjectConfigurationId configurationId,
        ProjectConfigurationVersion configurationVersion,
        String configurationDigest,
        String productProfileRef) {
    public ProjectProductSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        configurationId = Objects.requireNonNull(configurationId, "configurationId must not be null");
        configurationVersion = Objects.requireNonNull(configurationVersion, "configurationVersion must not be null");
        configurationDigest = require(configurationDigest, "configurationDigest");
        productProfileRef = require(productProfileRef, "productProfileRef");
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
