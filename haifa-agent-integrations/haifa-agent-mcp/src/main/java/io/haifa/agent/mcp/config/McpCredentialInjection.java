package io.haifa.agent.mcp.config;

import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialRequirement;
import java.util.Objects;

public record McpCredentialInjection(CredentialRequirement requirement, String targetName, String valuePrefix) {
    public McpCredentialInjection {
        Objects.requireNonNull(requirement, "requirement");
        if (targetName == null || !targetName.matches("[A-Za-z][A-Za-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("credential injection target name is invalid");
        }
        valuePrefix = Objects.requireNonNull(valuePrefix, "valuePrefix");
        if (requirement.exposureMode() == CredentialExposureMode.ENVIRONMENT_VARIABLE
                && !targetName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("environment credential target is invalid");
        }
    }
}
