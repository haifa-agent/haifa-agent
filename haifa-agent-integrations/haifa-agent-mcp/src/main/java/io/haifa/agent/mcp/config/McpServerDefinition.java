package io.haifa.agent.mcp.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record McpServerDefinition(
        McpServerId serverId,
        String displayName,
        boolean enabled,
        McpProtocolProfile protocol,
        McpTransportDefinition transport,
        McpToolImportPolicy importPolicy,
        McpConnectionPolicy connectionPolicy,
        List<McpCredentialInjection> discoveryCredentials,
        String bindingVersion,
        String bindingDigest) {
    public McpServerDefinition {
        Objects.requireNonNull(serverId, "serverId");
        displayName = text(displayName, "displayName");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(importPolicy, "importPolicy");
        Objects.requireNonNull(connectionPolicy, "connectionPolicy");
        discoveryCredentials = List.copyOf(Objects.requireNonNull(discoveryCredentials, "discoveryCredentials"));
        bindingVersion = text(bindingVersion, "bindingVersion");
        bindingDigest = text(bindingDigest, "bindingDigest");
        String expected = digestOf(
                serverId,
                displayName,
                enabled,
                protocol,
                transport,
                importPolicy,
                connectionPolicy,
                discoveryCredentials,
                bindingVersion);
        if (!expected.equals(bindingDigest)) {
            throw new IllegalArgumentException("MCP server binding digest does not match its definition");
        }
    }

    public static McpServerDefinition create(
            McpServerId serverId,
            String displayName,
            boolean enabled,
            McpProtocolProfile protocol,
            McpTransportDefinition transport,
            McpToolImportPolicy importPolicy,
            McpConnectionPolicy connectionPolicy,
            List<McpCredentialInjection> discoveryCredentials,
            String bindingVersion) {
        return new McpServerDefinition(
                serverId,
                displayName,
                enabled,
                protocol,
                transport,
                importPolicy,
                connectionPolicy,
                discoveryCredentials,
                bindingVersion,
                digestOf(
                        serverId,
                        displayName,
                        enabled,
                        protocol,
                        transport,
                        importPolicy,
                        connectionPolicy,
                        discoveryCredentials,
                        bindingVersion));
    }

    public String bindingReference() {
        return "mcp-server:" + serverId.value() + ":" + bindingVersion + ":" + bindingDigest;
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String digestOf(
            McpServerId serverId,
            String displayName,
            boolean enabled,
            McpProtocolProfile protocol,
            McpTransportDefinition transport,
            McpToolImportPolicy importPolicy,
            McpConnectionPolicy connectionPolicy,
            List<McpCredentialInjection> discoveryCredentials,
            String bindingVersion) {
        Map<String, Object> transportDocument;
        if (transport instanceof StreamableHttpDefinition http) {
            transportDocument = Map.ofEntries(
                    Map.entry("type", "streamable-http"),
                    Map.entry("endpoint", http.endpoint().toASCIIString()),
                    Map.entry("allowLoopbackHttp", http.allowLoopbackHttp()),
                    Map.entry(
                            "allowedOrigins",
                            http.allowedOrigins().stream().sorted().toList()),
                    Map.entry("connectTimeout", http.connectTimeout().toString()),
                    Map.entry("requestTimeout", http.requestTimeout().toString()),
                    Map.entry("idleTimeout", http.idleTimeout().toString()),
                    Map.entry("maxBodyBytes", http.maxBodyBytes()),
                    Map.entry("maxHeaderBytes", http.maxHeaderBytes()));
        } else if (transport instanceof StdioDefinition stdio) {
            transportDocument = Map.ofEntries(
                    Map.entry("type", "stdio"),
                    Map.entry("executable", stdio.executable()),
                    Map.entry("fixedArguments", stdio.fixedArguments()),
                    Map.entry("logicalWorkingDirectory", stdio.logicalWorkingDirectory()),
                    Map.entry(
                            "environmentNameAllowlist",
                            stdio.environmentNameAllowlist().stream().sorted().toList()),
                    Map.entry("startupTimeout", stdio.startupTimeout().toString()),
                    Map.entry("requestTimeout", stdio.requestTimeout().toString()),
                    Map.entry("idleTimeout", stdio.idleTimeout().toString()),
                    Map.entry("shutdownTimeout", stdio.shutdownTimeout().toString()),
                    Map.entry("maxFrameBytes", stdio.maxFrameBytes()),
                    Map.entry("maxStderrBytes", stdio.maxStderrBytes()));
        } else {
            throw new IllegalArgumentException("unsupported MCP transport definition");
        }
        Map<String, Object> importDocument = Map.ofEntries(
                Map.entry(
                        "allowedTools",
                        importPolicy.allowedTools().stream().sorted().toList()),
                Map.entry(
                        "deniedTools",
                        importPolicy.deniedTools().stream().sorted().toList()),
                Map.entry("aliasNamespace", importPolicy.aliasNamespace()),
                Map.entry("riskOverrides", enumMap(importPolicy.riskOverrides())),
                Map.entry("idempotencyOverrides", enumMap(importPolicy.idempotencyOverrides())),
                Map.entry(
                        "sideEffectOverrides",
                        importPolicy.sideEffectOverrides().entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .collect(java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey,
                                        entry -> entry.getValue().stream()
                                                .map(Enum::name)
                                                .sorted()
                                                .toList(),
                                        (left, right) -> left,
                                        java.util.LinkedHashMap::new))),
                Map.entry("approvalOverrides", enumMap(importPolicy.approvalOverrides())));
        Map<String, Object> connectionDocument = Map.of(
                "connectTimeout", connectionPolicy.connectTimeout().toString(),
                "requestTimeout", connectionPolicy.requestTimeout().toString(),
                "idleTimeout", connectionPolicy.idleTimeout().toString(),
                "shutdownTimeout", connectionPolicy.shutdownTimeout().toString(),
                "maxReconnectAttempts", connectionPolicy.maxReconnectAttempts());
        List<Map<String, Object>> credentialDocument = discoveryCredentials.stream()
                .map(injection -> Map.<String, Object>of(
                        "definitionId", injection.requirement().definitionId().value(),
                        "purpose", injection.requirement().purpose(),
                        "scopes",
                                injection.requirement().scopes().stream()
                                        .sorted()
                                        .toList(),
                        "exposureMode", injection.requirement().exposureMode().name(),
                        "targetName", injection.targetName(),
                        "valuePrefix", injection.valuePrefix()))
                .toList();
        return io.haifa.agent.mcp.protocol.McpCanonicalizer.digest(Map.ofEntries(
                Map.entry("serverId", serverId.value()),
                Map.entry("displayName", displayName),
                Map.entry("enabled", enabled),
                Map.entry("targetProtocolVersion", protocol.targetVersion()),
                Map.entry("transport", transportDocument),
                Map.entry("importPolicy", importDocument),
                Map.entry("connectionPolicy", connectionDocument),
                Map.entry("discoveryCredentials", credentialDocument),
                Map.entry("bindingVersion", bindingVersion)));
    }

    private static Map<String, String> enumMap(Map<String, ? extends Enum<?>> source) {
        return source.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().name(),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
    }
}
