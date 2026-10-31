package io.haifa.agent.cli.remote;

import io.haifa.agent.project.domain.ProjectId;
import java.util.Objects;
import java.util.Set;

/** Safe, immutable protocol context exposed by one initialized remote Coding Host connection. */
public record RemoteCodingClientInfo(
        String protocolVersion,
        String hostName,
        ProjectId projectId,
        String clientId,
        String principalType,
        Set<String> capabilities,
        Set<String> unsupported) {

    public RemoteCodingClientInfo {
        protocolVersion = requireText(protocolVersion, "protocolVersion");
        hostName = requireText(hostName, "hostName");
        projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        clientId = requireText(clientId, "clientId");
        principalType = requireText(principalType, "principalType");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        unsupported = Set.copyOf(Objects.requireNonNull(unsupported, "unsupported must not be null"));
    }

    public boolean supports(String capability) {
        return capabilities.contains(capability);
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
