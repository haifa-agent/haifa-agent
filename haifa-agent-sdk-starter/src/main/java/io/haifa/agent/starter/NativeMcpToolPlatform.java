package io.haifa.agent.starter;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.credential.api.SecretRedactor;
import io.haifa.agent.credential.core.DefaultCredentialBroker;
import io.haifa.agent.credential.core.DefaultSecretRedactor;
import io.haifa.agent.mcp.client.McpClientFactory;
import io.haifa.agent.mcp.client.McpConnectionManager;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.tool.InMemoryMcpToolBindingStore;
import io.haifa.agent.mcp.tool.McpContentMapper;
import io.haifa.agent.mcp.tool.McpDiscoveryContext;
import io.haifa.agent.mcp.tool.McpToolDefinitionMapper;
import io.haifa.agent.mcp.tool.McpToolDiscoveryService;
import io.haifa.agent.mcp.tool.McpToolImportCandidate;
import io.haifa.agent.mcp.tool.McpToolProvider;
import io.haifa.agent.sdk.api.AgentDiagnostic;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.tool.core.ToolDefinitionCanonicalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Native MCP Client platform owned by one Agent.
 *
 * <p>It connects the declared {@link McpServerSpec}s through the existing MCP Integration, discovers
 * the explicitly allowed Tools, and hands the result to the SDK as ordinary {@link ToolRegistration}s
 * that join the single Tool catalog freeze. The Agent owns this platform: closing the Agent closes
 * every MCP connection and HTTP resource it opened, and a failed build closes them too.
 *
 * <p>This is an SDK assembly detail, not a stable extension point: it is package-private on purpose.
 * Applications declare MCP servers with {@link McpServerSpec} and never see this class.
 */
final class NativeMcpToolPlatform implements AutoCloseable {
    private static final int MAX_DISCOVERY_PAGES = 32;
    private static final int MAX_DISCOVERY_TOOLS = 256;
    private static final int MAX_DISCOVERY_SCHEMA_CHARS = 4 * 1024 * 1024;
    private static final Duration DISCOVERY_DEADLINE = Duration.ofSeconds(60);

    private final McpConnectionManager connections;
    private final CredentialBroker credentials;
    private final List<ToolRegistration> registrations;
    private final List<AgentDiagnostic> diagnostics;
    private final AtomicBoolean closed = new AtomicBoolean();

    private NativeMcpToolPlatform(
            McpConnectionManager connections,
            CredentialBroker credentials,
            List<ToolRegistration> registrations,
            List<AgentDiagnostic> diagnostics) {
        this.connections = connections;
        this.credentials = credentials;
        this.registrations = List.copyOf(registrations);
        this.diagnostics = List.copyOf(diagnostics);
    }

    /**
     * Connects every declared MCP server and imports its allowlisted Tools.
     *
     * <p>A required server that cannot be reached, negotiated, reviewed or fully resolved fails
     * closed: every connection opened so far is released and the assembly fails. An optional server
     * that fails contributes no Tool and reports one safe diagnostic; a partially usable server never
     * registers an unavailable Tool.
     *
     * @param specs declared MCP servers in declaration order
     * @param tenant trusted tenant used for discovery
     * @param principal trusted principal used for discovery
     * @param environment resolver for credential environment variables
     * @param clientFactory MCP client factory used for every connection
     * @return the connected platform; never {@code null}
     */
    static NativeMcpToolPlatform connect(
            List<McpServerSpec> specs,
            TenantRef tenant,
            PrincipalRef principal,
            Function<String, String> environment,
            McpClientFactory clientFactory) {
        List<McpServerSpec> declared = List.copyOf(Objects.requireNonNull(specs, "specs must not be null"));
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(clientFactory, "clientFactory must not be null");

        List<AgentDiagnostic> diagnostics = new ArrayList<>();
        Set<String> declaredNames = new LinkedHashSet<>();
        Map<String, McpServerSpec> usable = new LinkedHashMap<>();
        List<McpServerDefinition> definitions = new ArrayList<>();
        Map<String, String> secrets = new LinkedHashMap<>();
        for (McpServerSpec spec : declared) {
            Objects.requireNonNull(spec, "MCP server spec must not be null");
            if (!declaredNames.add(spec.name())) {
                throw failure(
                        "MCP_SERVER_NAME_CONFLICT",
                        "MCP connection name " + spec.name() + " is declared more than once");
            }
            try {
                // Resolve the whole server before recording any of it, so a half-resolved optional
                // server never leaves a definition behind without its spec and credentials.
                McpServerDefinition definition = spec.toServerDefinition();
                Map<String, String> resolved = resolveSecrets(spec, environment);
                definitions.add(definition);
                secrets.putAll(resolved);
                usable.put(spec.name(), spec);
            } catch (RuntimeException exception) {
                degradeOrFail(spec, diagnostics, "MCP_SERVER_CONFIGURATION_INVALID", exception);
            }
        }

        SecretRedactor redactor = new DefaultSecretRedactor(secrets.values());
        CredentialBroker broker = new DefaultCredentialBroker(Map.copyOf(secrets), redactor);
        var connections = new McpConnectionManager(definitions, clientFactory);
        if (definitions.isEmpty()) {
            return new NativeMcpToolPlatform(connections, broker, List.of(), diagnostics);
        }

        var bindings = new InMemoryMcpToolBindingStore();
        var mapper = new McpToolDefinitionMapper(new ToolDefinitionCanonicalizer(), bindings);
        var discovery = new McpToolDiscoveryService(
                connections,
                mapper,
                broker,
                Clock.systemUTC(),
                MAX_DISCOVERY_PAGES,
                MAX_DISCOVERY_TOOLS,
                MAX_DISCOVERY_SCHEMA_CHARS,
                DISCOVERY_DEADLINE);
        List<ToolRegistration> registrations = new ArrayList<>();
        try {
            for (McpServerDefinition definition : definitions) {
                McpServerSpec spec = usable.get(definition.serverId().value());
                try {
                    registrations.addAll(importTools(
                            spec,
                            definition,
                            discovery,
                            connections,
                            bindings,
                            redactor,
                            new McpDiscoveryContext(tenant, principal)));
                } catch (RuntimeException exception) {
                    degradeOrFail(spec, diagnostics, "MCP_SERVER_UNAVAILABLE", exception);
                }
            }
        } catch (RuntimeException | Error exception) {
            connections.close();
            throw exception;
        }
        return new NativeMcpToolPlatform(connections, broker, registrations, diagnostics);
    }

    /** Tool registrations imported from every usable MCP server, in declaration order. */
    List<ToolRegistration> registrations() {
        return registrations;
    }

    /** Non-secret diagnostics describing optional MCP servers that contributed nothing. */
    List<AgentDiagnostic> diagnostics() {
        return diagnostics;
    }

    /** The credential broker holding the MCP header secrets resolved for this platform. */
    CredentialBroker credentials() {
        return credentials;
    }

    /** Releases every MCP connection and HTTP resource this platform opened. Repeated calls do nothing. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        connections.close();
    }

    private static List<ToolRegistration> importTools(
            McpServerSpec spec,
            McpServerDefinition definition,
            McpToolDiscoveryService discovery,
            McpConnectionManager connections,
            InMemoryMcpToolBindingStore bindings,
            SecretRedactor redactor,
            McpDiscoveryContext context) {
        List<McpToolImportCandidate> candidates = discovery.discover(definition.serverId(), context);
        Map<String, McpToolImportCandidate> byRemoteName = candidates.stream()
                .collect(Collectors.toMap(McpToolImportCandidate::remoteName, Function.identity(), (a, b) -> a));
        List<String> unavailable = spec.allowedTools().stream()
                .sorted()
                .filter(name -> !byRemoteName.containsKey(name)
                        || !byRemoteName.get(name).enabled())
                .map(name -> describeUnavailable(name, byRemoteName.get(name)))
                .toList();
        if (!unavailable.isEmpty()) {
            throw failure(
                    "MCP_TOOL_UNAVAILABLE",
                    "MCP server " + spec.name() + " could not import allowed Tools: " + unavailable);
        }
        var provider =
                new McpToolProvider(definition.serverId(), bindings, connections, new McpContentMapper(redactor));
        List<ToolRegistration> registrations = new ArrayList<>();
        for (String remoteName : spec.allowedTools()) {
            McpToolImportCandidate candidate = byRemoteName.get(remoteName);
            registrations.add(new ToolRegistration(
                    candidate.alias().orElseThrow(),
                    candidate.definition().orElseThrow(),
                    candidate.binding().orElseThrow().bindingReference(),
                    provider));
        }
        return registrations;
    }

    private static String describeUnavailable(String remoteName, McpToolImportCandidate candidate) {
        if (candidate == null) return remoteName + " (not discovered)";
        String codes = candidate.diagnostics().stream()
                .map(diagnostic -> diagnostic.code())
                .sorted()
                .collect(Collectors.joining(","));
        return remoteName + " (" + (codes.isEmpty() ? "not importable" : codes) + ")";
    }

    private static Map<String, String> resolveSecrets(McpServerSpec spec, Function<String, String> environment) {
        Map<String, String> secrets = new LinkedHashMap<>();
        for (McpServerSpec.HeaderCredential credential : spec.credentials()) {
            String secret = environment.apply(credential.environmentVariable());
            if (secret == null || secret.isBlank()) {
                throw failure(
                        "MCP_CREDENTIAL_UNAVAILABLE",
                        "MCP server " + spec.name() + " requires environment variable "
                                + credential.environmentVariable());
            }
            secrets.put(credential.credentialId(), secret);
        }
        return secrets;
    }

    private static void degradeOrFail(
            McpServerSpec spec, List<AgentDiagnostic> diagnostics, String fallbackCode, RuntimeException exception) {
        String code = exception instanceof HaifaAgentException haifa ? haifa.code() : fallbackCode;
        if (spec.requirement() == McpServerRequirement.REQUIRED) {
            if (exception instanceof HaifaAgentException haifa) throw haifa;
            throw new HaifaAgentException(
                    code,
                    "product.assemble",
                    "mcp",
                    "required MCP server " + spec.name() + " is unavailable",
                    exception);
        }
        diagnostics.add(new AgentDiagnostic(
                AgentDiagnostic.Severity.WARNING, code, "optional MCP server " + spec.name() + " contributed no Tool"));
    }

    private static HaifaAgentException failure(String code, String safeMessage) {
        return new HaifaAgentException(code, "product.assemble", "mcp", safeMessage);
    }
}
