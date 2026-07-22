package io.haifa.agent.cli;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

record CliConfiguration(
        Model model,
        Set<String> enabledTools,
        List<McpServer> mcpServers,
        ApprovalMode approval,
        Duration timeout,
        int maxIterations,
        long maxToolCalls) {
    private static final Set<String> DEFAULT_TOOLS = Set.of(
            "file.list",
            "file.stat",
            "file.read",
            "file.search",
            "file.create",
            "file.write",
            "file.delete",
            "file.move");

    CliConfiguration {
        model = Objects.requireNonNull(model, "model must not be null");
        enabledTools =
                Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(enabledTools, "enabledTools must not be null")));
        mcpServers = List.copyOf(Objects.requireNonNull(mcpServers, "mcpServers must not be null"));
        if (!DEFAULT_TOOLS.containsAll(enabledTools)) {
            throw new IllegalArgumentException("CLI currently supports only local file tools: " + DEFAULT_TOOLS);
        }
        if (mcpServers.stream().map(McpServer::id).distinct().count() != mcpServers.size()) {
            throw new IllegalArgumentException("MCP server ids must be unique");
        }
        approval = Objects.requireNonNull(approval, "approval must not be null");
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        if (maxIterations < 1 || maxToolCalls < 1)
            throw new IllegalArgumentException("runtime limits must be positive");
    }

    static CliConfiguration defaults() {
        return new CliConfiguration(
                new Model(
                        "deepseek",
                        "deepseek-v4-pro",
                        URI.create("https://api.deepseek.com"),
                        "env://DEEPSEEK_API_KEY"),
                DEFAULT_TOOLS,
                List.of(),
                ApprovalMode.ASK,
                Duration.ofMinutes(5),
                50,
                32);
    }

    record Model(String providerId, String modelId, URI endpoint, String credentialRef) {
        Model {
            providerId = text(providerId, "model.providerId");
            modelId = text(modelId, "model.modelId");
            endpoint = Objects.requireNonNull(endpoint, "model.endpoint must not be null");
            credentialRef = text(credentialRef, "model.credentialRef");
            if (!credentialRef.startsWith("env://")) {
                throw new IllegalArgumentException("model.credentialRef must use env://");
            }
        }
    }

    record McpServer(
            String id,
            String displayName,
            URI endpoint,
            boolean allowLoopbackHttp,
            Set<String> allowedTools,
            String aliasNamespace,
            String policyProfile,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration idleTimeout,
            int maxBodyBytes,
            int maxHeaderBytes,
            int maxReconnectAttempts) {
        McpServer {
            id = text(id, "mcp server id");
            displayName = text(displayName, "mcp server displayName");
            endpoint = Objects.requireNonNull(endpoint, "mcp server endpoint must not be null");
            allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "mcp allowedTools must not be null"));
            if (allowedTools.isEmpty()) throw new IllegalArgumentException("mcp allowedTools must not be empty");
            aliasNamespace = text(aliasNamespace, "mcp aliasNamespace");
            policyProfile = text(policyProfile, "mcp policyProfile");
            if (!Set.of("conservative", "utility").contains(policyProfile)) {
                throw new IllegalArgumentException("mcp policyProfile must be conservative or utility");
            }
            positive(connectTimeout, "mcp connectTimeout");
            positive(requestTimeout, "mcp requestTimeout");
            positive(idleTimeout, "mcp idleTimeout");
            if (maxBodyBytes < 1024 || maxBodyBytes > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("mcp maxBodyBytes is out of range");
            }
            if (maxHeaderBytes < 1024 || maxHeaderBytes > 256 * 1024) {
                throw new IllegalArgumentException("mcp maxHeaderBytes is out of range");
            }
            if (maxReconnectAttempts < 0 || maxReconnectAttempts > 8) {
                throw new IllegalArgumentException("mcp maxReconnectAttempts is out of range");
            }
        }

        private static void positive(Duration value, String field) {
            Objects.requireNonNull(value, field + " must not be null");
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(field + " must be positive");
            }
        }
    }

    static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    static Set<String> defaultTools() {
        return DEFAULT_TOOLS;
    }
}
