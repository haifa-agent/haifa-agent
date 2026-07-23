package io.haifa.agent.cli;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.openai.AliyunBailianProviderFactory;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

record CliConfiguration(
        Model model,
        Set<String> enabledTools,
        List<McpServer> mcpServers,
        Execution execution,
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
            "file.move",
            "execution.run");
    private static final Set<String> DEFAULT_ENVIRONMENT = Set.of(
            "PATH", "HOME", "USERPROFILE", "TMP", "TEMP", "SystemRoot", "JAVA_HOME", "MAVEN_OPTS", "GRADLE_USER_HOME");

    CliConfiguration {
        model = Objects.requireNonNull(model, "model must not be null");
        enabledTools =
                Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(enabledTools, "enabledTools must not be null")));
        mcpServers = List.copyOf(Objects.requireNonNull(mcpServers, "mcpServers must not be null"));
        execution = Objects.requireNonNull(execution, "execution must not be null");
        if (!DEFAULT_TOOLS.containsAll(enabledTools)) {
            throw new IllegalArgumentException("CLI supports only built-in local tools: " + DEFAULT_TOOLS);
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
                new Execution(
                        "auto",
                        null,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(30),
                        50 * 1024,
                        2000,
                        8,
                        DEFAULT_ENVIRONMENT),
                ApprovalMode.ASK,
                Duration.ofMinutes(5),
                50,
                32);
    }

    record Model(
            String providerId, String modelId, URI endpoint, String credentialRef, String workspaceId, String region) {
        Model(String providerId, String modelId, URI endpoint, String credentialRef) {
            this(providerId, modelId, endpoint, credentialRef, null, null);
        }

        Model {
            providerId = text(providerId, "model.providerId");
            modelId = text(modelId, "model.modelId");
            credentialRef = text(credentialRef, "model.credentialRef");
            if (!credentialRef.startsWith("env://")) {
                throw new IllegalArgumentException("model.credentialRef must use env://");
            }
            if (providerId.equals(AliyunBailianProviderFactory.PROVIDER_ID.value())) {
                var configuration = new AliyunBailianProviderFactory.ProviderConfiguration(
                        "cli-v1", workspaceId, region, new CredentialRef(credentialRef));
                URI derivedEndpoint = configuration.endpoint();
                if (endpoint != null && !normalizeEndpoint(endpoint).equals(derivedEndpoint)) {
                    throw new IllegalArgumentException(
                            "model.endpoint must match the endpoint derived from workspaceId and region");
                }
                workspaceId = configuration.workspaceId();
                region = configuration.region();
                endpoint = derivedEndpoint;
            } else {
                endpoint = normalizeEndpoint(Objects.requireNonNull(endpoint, "model.endpoint must not be null"));
                workspaceId = optionalText(workspaceId);
                region = optionalText(region);
            }
        }

        private static URI normalizeEndpoint(URI endpoint) {
            String value = endpoint.normalize().toString();
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            return URI.create(value);
        }

        private static String optionalText(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    record Execution(
            String shell,
            Path shellPath,
            Duration defaultTimeout,
            Duration maximumTimeout,
            int maxOutputBytes,
            int maxOutputLines,
            int maxProcesses,
            Set<String> inheritEnvironment) {
        private static final Set<String> SHELLS = Set.of("auto", "bash", "powershell");

        Execution {
            shell = text(shell, "execution.shell").toLowerCase(java.util.Locale.ROOT);
            if (!SHELLS.contains(shell)) throw new IllegalArgumentException("execution.shell is unsupported");
            if (shellPath != null) {
                if (!shellPath.isAbsolute()) throw new IllegalArgumentException("execution.shellPath must be absolute");
                if (shell.equals("auto")) {
                    throw new IllegalArgumentException("execution.shellPath requires bash or powershell");
                }
            }
            positive(defaultTimeout, "execution.defaultTimeout");
            positive(maximumTimeout, "execution.maximumTimeout");
            if (defaultTimeout.compareTo(maximumTimeout) > 0 || maximumTimeout.compareTo(Duration.ofMinutes(30)) > 0) {
                throw new IllegalArgumentException("execution timeout configuration is out of range");
            }
            if (maxOutputBytes < 1024 || maxOutputBytes > 1024 * 1024) {
                throw new IllegalArgumentException("execution.maxOutputBytes is out of range");
            }
            if (maxOutputLines < 1 || maxOutputLines > 10_000) {
                throw new IllegalArgumentException("execution.maxOutputLines is out of range");
            }
            if (maxProcesses < 1 || maxProcesses > 64) {
                throw new IllegalArgumentException("execution.maxProcesses is out of range");
            }
            inheritEnvironment = Set.copyOf(
                    Objects.requireNonNull(inheritEnvironment, "execution.inheritEnvironment must not be null"));
            if (inheritEnvironment.stream().anyMatch(name -> !name.matches("[A-Za-z_][A-Za-z0-9_]*"))) {
                throw new IllegalArgumentException("execution.inheritEnvironment contains an invalid name");
            }
            if (inheritEnvironment.stream()
                    .map(name -> name.toUpperCase(java.util.Locale.ROOT))
                    .anyMatch(Execution::looksSensitive)) {
                throw new IllegalArgumentException("execution.inheritEnvironment contains a secret-like name");
            }
        }

        private static boolean looksSensitive(String name) {
            return name.contains("API_KEY")
                    || name.contains("ACCESS_KEY")
                    || name.contains("PRIVATE_KEY")
                    || name.contains("PASSWORD")
                    || name.contains("SECRET")
                    || name.contains("TOKEN")
                    || name.contains("CREDENTIAL");
        }

        private static void positive(Duration value, String field) {
            Objects.requireNonNull(value, field + " must not be null");
            if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(field + " must be positive");
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
