package io.haifa.agent.mcp.tool;

import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.protocol.McpCanonicalizer;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record McpToolBindingSnapshot(
        String bindingReference,
        String bindingDigest,
        String serverId,
        String serverBindingVersion,
        String serverBindingDigest,
        String remoteToolName,
        String remoteDefinitionDigest,
        String targetProtocolVersion,
        String negotiatedProtocolVersion,
        String transportIdentityReference,
        List<CredentialRequirement> credentialRequirements,
        ToolDefinitionHash localDefinitionHash) {
    public McpToolBindingSnapshot {
        bindingReference = text(bindingReference, "bindingReference");
        bindingDigest = text(bindingDigest, "bindingDigest");
        serverId = text(serverId, "serverId");
        serverBindingVersion = text(serverBindingVersion, "serverBindingVersion");
        serverBindingDigest = text(serverBindingDigest, "serverBindingDigest");
        remoteToolName = text(remoteToolName, "remoteToolName");
        remoteDefinitionDigest = text(remoteDefinitionDigest, "remoteDefinitionDigest");
        targetProtocolVersion = text(targetProtocolVersion, "targetProtocolVersion");
        negotiatedProtocolVersion = text(negotiatedProtocolVersion, "negotiatedProtocolVersion");
        transportIdentityReference = text(transportIdentityReference, "transportIdentityReference");
        credentialRequirements = List.copyOf(Objects.requireNonNull(credentialRequirements, "credentialRequirements"));
        Objects.requireNonNull(localDefinitionHash, "localDefinitionHash");
        if (!bindingReference.equals("mcp-tool:" + bindingDigest)) {
            throw new IllegalArgumentException("binding reference does not match binding digest");
        }
    }

    public static McpToolBindingSnapshot create(
            McpServerDefinition server,
            String remoteToolName,
            String remoteDefinitionDigest,
            String negotiatedProtocolVersion,
            List<CredentialRequirement> credentialRequirements,
            ToolDefinitionHash localDefinitionHash) {
        Map<String, Object> document = Map.of(
                "serverId", server.serverId().value(),
                "serverBindingVersion", server.bindingVersion(),
                "serverBindingDigest", server.bindingDigest(),
                "remoteToolName", remoteToolName,
                "remoteDefinitionDigest", remoteDefinitionDigest,
                "targetProtocolVersion", server.protocol().targetVersion(),
                "negotiatedProtocolVersion", negotiatedProtocolVersion,
                "transportIdentityReference", server.transport().identityReference(),
                "credentialRequirements",
                        credentialRequirements.stream()
                                .map(requirement -> Map.of(
                                        "definitionId",
                                                requirement.definitionId().value(),
                                        "purpose", requirement.purpose(),
                                        "scopes",
                                                requirement.scopes().stream()
                                                        .sorted()
                                                        .toList(),
                                        "exposureMode",
                                                requirement.exposureMode().name()))
                                .toList(),
                "localDefinitionHash", localDefinitionHash.value());
        String digest = McpCanonicalizer.digest(document);
        return new McpToolBindingSnapshot(
                "mcp-tool:" + digest,
                digest,
                server.serverId().value(),
                server.bindingVersion(),
                server.bindingDigest(),
                remoteToolName,
                remoteDefinitionDigest,
                server.protocol().targetVersion(),
                negotiatedProtocolVersion,
                server.transport().identityReference(),
                credentialRequirements,
                localDefinitionHash);
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
