package io.haifa.agent.starter;

import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.mcp.config.McpConnectionPolicy;
import io.haifa.agent.mcp.config.McpCredentialInjection;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.McpServerId;
import io.haifa.agent.mcp.config.McpToolImportPolicy;
import io.haifa.agent.mcp.config.StreamableHttpDefinition;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Declaration of one remote MCP server this Agent consumes as an MCP Client.
 *
 * <p>The spec is the whole public surface a Pure Java application needs: a named connection, its
 * transport endpoint, the Tools it is explicitly allowed to import, the stable Tool name prefix those
 * Tools receive, the local governance preset applied to them, and whether the connection is required.
 * Connection management, Tool discovery, protocol negotiation, schema mapping and Tool binding stay
 * inside the existing MCP Integration and are never exposed here.
 *
 * <p>Instances are immutable; every configuration method returns a new spec, so a spec can be shared
 * and reused safely.
 *
 * <pre>{@code
 * var search = McpServerSpec.streamableHttp("enterprise-search", URI.create("https://partner.example.com/mcp"))
 *         .allowTools("search_courses", "search_policies", "search_jobs")
 *         .toolNamePrefix("enterprise")
 *         .readOnly()
 *         .required();
 * }</pre>
 *
 * <p>This type describes an MCP server Haifa <em>connects to</em>. It is not an MCP server hosting or
 * publishing API, and Haifa exposes no Tool, Resource or Prompt over MCP.
 */
public final class McpServerSpec {
    /** MCP protocol revision the Starter pins by default; override it with {@link #protocolVersion(String)}. */
    public static final String DEFAULT_PROTOCOL_VERSION = McpProtocolProfile.VERSION_2025_11_25;

    private static final String BINDING_VERSION = "1.0.0";
    /**
     * Headers the HTTP and MCP transports own. A credential header may not set or append to them,
     * because the transport already decides their value for every request.
     */
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "accept",
            "connection",
            "content-length",
            "content-type",
            "host",
            "last-event-id",
            "mcp-method",
            "mcp-protocol-version",
            "mcp-session-id",
            "transfer-encoding",
            "upgrade");

    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 32 * 1024;

    private final String name;
    private final URI endpoint;
    private final Set<String> allowedTools;
    private final String toolNamePrefix;
    private final McpServerRequirement requirement;
    private final boolean readOnly;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final boolean allowLoopbackHttp;
    private final String protocolVersion;
    private final Map<String, HeaderCredential> headerCredentials;

    private McpServerSpec(
            String name,
            URI endpoint,
            Set<String> allowedTools,
            String toolNamePrefix,
            McpServerRequirement requirement,
            boolean readOnly,
            Duration connectTimeout,
            Duration requestTimeout,
            boolean allowLoopbackHttp,
            String protocolVersion,
            Map<String, HeaderCredential> headerCredentials) {
        this.name = name;
        this.endpoint = endpoint;
        this.allowedTools = allowedTools;
        this.toolNamePrefix = toolNamePrefix;
        this.requirement = requirement;
        this.readOnly = readOnly;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.allowLoopbackHttp = allowLoopbackHttp;
        this.protocolVersion = protocolVersion;
        this.headerCredentials = headerCredentials;
    }

    /**
     * Declares a Streamable HTTP MCP server under a stable connection name.
     *
     * <p>The connection name, not the URL, is the server's identity: it names the connection in
     * diagnostics and, when it is short enough to stay model-safe, supplies the default Tool name
     * prefix. HTTPS is required unless loopback HTTP is enabled explicitly for local development.
     *
     * <p>A longer connection name remains legal and simply carries no derivable prefix: declare one
     * with {@link #toolNamePrefix(String)} or {@link #streamableHttp(String, URI, String)}. Connection
     * identity and Tool naming are separate concepts on purpose.
     *
     * @param name lowercase stable connection name, such as {@code enterprise-search}
     * @param endpoint absolute credential-free MCP endpoint without query or fragment
     * @return a new spec with safe defaults and no allowed Tool yet
     */
    public static McpServerSpec streamableHttp(String name, URI endpoint) {
        return new McpServerSpec(
                requireConnectionName(name),
                requireEndpoint(endpoint),
                Set.of(),
                null,
                McpServerRequirement.REQUIRED,
                false,
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                false,
                DEFAULT_PROTOCOL_VERSION,
                Map.of());
    }

    /**
     * Declares a Streamable HTTP MCP server with an explicit Tool name prefix.
     *
     * <p>Use this when the connection name is longer than a model-safe Tool name prefix allows, so a
     * long descriptive connection name can still carry a short stable prefix.
     *
     * @param name lowercase stable connection name, such as {@code enterprise-search}
     * @param endpoint absolute credential-free MCP endpoint without query or fragment
     * @param toolNamePrefix lowercase prefix matching {@code [a-z][a-z0-9_]{0,31}}
     * @return a new spec with safe defaults and no allowed Tool yet
     */
    public static McpServerSpec streamableHttp(String name, URI endpoint, String toolNamePrefix) {
        return streamableHttp(name, endpoint).toolNamePrefix(toolNamePrefix);
    }

    /**
     * Adds remote Tool names to the explicit import allowlist.
     *
     * <p>There is no allow-all mode: a server contributes exactly the Tools named here, and a named
     * Tool the server does not offer is treated as a failure of that connection.
     *
     * @param remoteToolNames remote Tool names as the MCP server publishes them
     * @return a new spec including these Tools
     */
    public McpServerSpec allowTools(String... remoteToolNames) {
        return allowTools(List.of(Objects.requireNonNull(remoteToolNames, "remoteToolNames must not be null")));
    }

    /**
     * Adds remote Tool names to the explicit import allowlist.
     *
     * @param remoteToolNames remote Tool names as the MCP server publishes them
     * @return a new spec including these Tools
     */
    public McpServerSpec allowTools(Collection<String> remoteToolNames) {
        Set<String> merged = new LinkedHashSet<>(allowedTools);
        Objects.requireNonNull(remoteToolNames, "remoteToolNames must not be null")
                .forEach(value -> merged.add(requireRemoteToolName(value)));
        return withAllowedTools(Collections.unmodifiableSet(merged));
    }

    /**
     * Sets the stable prefix every imported Tool name receives, such as {@code enterprise} producing
     * {@code enterprise_search_courses}.
     *
     * <p>The prefix is deterministic and part of the frozen Tool binding; it is never generated from
     * connection ordering.
     *
     * @param prefix lowercase prefix matching {@code [a-z][a-z0-9_]{0,31}}
     * @return a new spec using this prefix
     */
    public McpServerSpec toolNamePrefix(String prefix) {
        return withToolNamePrefix(requireToolNamePrefix(prefix));
    }

    /**
     * Fails the Agent build when this MCP server is unusable. This is the default.
     *
     * @return a new required spec
     */
    public McpServerSpec required() {
        return withRequirement(McpServerRequirement.REQUIRED);
    }

    /**
     * Lets the Agent start without this MCP server when it is unusable; no Tool from it is registered
     * and a safe diagnostic is reported instead.
     *
     * @return a new optional spec
     */
    public McpServerSpec optional() {
        return withRequirement(McpServerRequirement.OPTIONAL);
    }

    /**
     * Applies the locally trusted read-only governance preset to every imported Tool: low risk,
     * idempotent, network access only, and approval decided by the product policy.
     *
     * <p>This is a local declaration by the host application. It is never derived from what the remote
     * MCP server claims about itself. Without it, imported Tools keep the conservative default of high
     * risk, unknown idempotency, external mutation and mandatory approval.
     *
     * @return a new spec using the read-only preset
     */
    public McpServerSpec readOnly() {
        return withReadOnly(true);
    }

    /**
     * Sets the transport connect timeout.
     *
     * @param value positive connect timeout
     * @return a new spec using this timeout
     */
    public McpServerSpec connectTimeout(Duration value) {
        return withTimeouts(requirePositive(value, "connectTimeout"), requestTimeout);
    }

    /**
     * Sets the per-request timeout used for discovery and Tool calls.
     *
     * @param value positive request timeout
     * @return a new spec using this timeout
     */
    public McpServerSpec requestTimeout(Duration value) {
        return withTimeouts(connectTimeout, requirePositive(value, "requestTimeout"));
    }

    /**
     * Allows plain HTTP for a loopback endpoint. Local development and tests only.
     *
     * @return a new spec accepting loopback HTTP
     */
    public McpServerSpec allowLoopbackHttp() {
        return withLoopbackHttp(true);
    }

    /**
     * Pins the MCP protocol revision this connection negotiates.
     *
     * @param value supported MCP protocol revision, such as {@code 2025-11-25}
     * @return a new spec pinned to this revision
     */
    public McpServerSpec protocolVersion(String value) {
        return withProtocolVersion(Objects.requireNonNull(value, "protocolVersion must not be null")
                .trim());
    }

    /**
     * Injects one HTTP header whose value is read from an environment variable at build and call time.
     *
     * <p>The spec stores only the variable name. The secret is read from the environment once during
     * assembly, held by the ordinary credential boundary, and injected from there into discovery and
     * Tool-call requests, so changing the environment afterwards does not rotate a live token. It never
     * enters the spec, the Tool definition, diagnostics or logs.
     *
     * <p>Headers the HTTP and MCP transports own, such as {@code Content-Type}, {@code Accept},
     * {@code MCP-Protocol-Version}, {@code Mcp-Session-Id} or {@code Host}, are rejected.
     *
     * @param headerName HTTP header name
     * @param environmentVariableName environment variable holding the secret
     * @return a new spec injecting this header
     */
    public McpServerSpec header(String headerName, String environmentVariableName) {
        return header(headerName, environmentVariableName, "");
    }

    /**
     * Injects an {@code Authorization: Bearer} header whose token is read from an environment
     * variable during assembly.
     *
     * @param environmentVariableName environment variable holding the token
     * @return a new spec sending the bearer token
     */
    public McpServerSpec bearerTokenFromEnvironment(String environmentVariableName) {
        return header("Authorization", environmentVariableName, "Bearer ");
    }

    /**
     * Injects a dynamically resolved Bearer token into the {@code Authorization} header.
     *
     * <p>The token supplier is invoked dynamically on every discovery and Tool-call request, allowing
     * tokens to be refreshed across long-running agent sessions or retrieved from dynamic sources.
     * If the supplier returns a token with a redundant {@code Bearer } prefix, it is normalized safely.
     *
     * @param tokenSupplier supplier returning the bearer token
     * @return a new spec injecting this bearer token
     */
    public McpServerSpec bearerToken(Supplier<String> tokenSupplier) {
        Objects.requireNonNull(tokenSupplier, "tokenSupplier must not be null");
        return header(
                "Authorization",
                () -> {
                    String token = tokenSupplier.get();
                    if (token == null) return null;
                    String trimmed = token.trim();
                    if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                        return trimmed.substring(7).trim();
                    }
                    return trimmed;
                },
                "Bearer ");
    }

    /**
     * Injects one dynamically resolved HTTP header.
     *
     * <p>The supplier is invoked on every discovery and Tool-call request. Headers owned by the HTTP
     * or MCP transport are rejected.
     *
     * @param headerName HTTP header name
     * @param valueSupplier supplier returning the header value
     * @return a new spec injecting this header
     */
    public McpServerSpec header(String headerName, Supplier<String> valueSupplier) {
        return header(headerName, valueSupplier, "");
    }

    /** The stable connection name; the server's identity in diagnostics and Tool provenance. */
    public String name() {
        return name;
    }

    /** The declared MCP endpoint. */
    public URI endpoint() {
        return endpoint;
    }

    /** The explicit remote Tool allowlist in declaration order. */
    public Set<String> allowedTools() {
        return allowedTools;
    }

    /**
     * The stable prefix applied to every imported Tool name.
     *
     * <p>This is the explicitly declared prefix, or one derived from the connection name when that
     * name is short enough to stay model-safe.
     *
     * @return the effective Tool name prefix
     * @throws IllegalStateException when no prefix was declared and the connection name cannot derive
     *     a model-safe one
     */
    public String toolNamePrefix() {
        return effectiveToolNamePrefix();
    }

    /** Whether an unusable connection fails the build or degrades to no Tools. */
    public McpServerRequirement requirement() {
        return requirement;
    }

    /** The local Tool name this spec produces for one remote Tool name. */
    public String localToolName(String remoteToolName) {
        return effectiveToolNamePrefix() + "_" + requireRemoteToolName(remoteToolName);
    }

    @Override
    public String toString() {
        return "McpServerSpec[name=" + name + ", endpoint=" + endpoint + ", toolNamePrefix="
                + (toolNamePrefix == null ? "<derived from name>" : toolNamePrefix)
                + ", requirement=" + requirement + ", allowedTools=" + allowedTools + "]";
    }

    McpServerDefinition toServerDefinition() {
        if (allowedTools.isEmpty()) {
            throw new IllegalArgumentException(
                    "MCP server " + name + " must allow at least one Tool; there is no allow-all import mode");
        }
        var transport = new StreamableHttpDefinition(
                endpoint,
                allowLoopbackHttp,
                Set.of(StreamableHttpDefinition.origin(endpoint)),
                connectTimeout,
                requestTimeout,
                requestTimeout,
                MAX_BODY_BYTES,
                MAX_HEADER_BYTES);
        return McpServerDefinition.create(
                new McpServerId(name),
                name,
                true,
                new McpProtocolProfile(protocolVersion),
                transport,
                importPolicy(),
                new McpConnectionPolicy(connectTimeout, requestTimeout, requestTimeout, Duration.ofSeconds(5), 1),
                credentialInjections(),
                BINDING_VERSION);
    }

    List<HeaderCredential> credentials() {
        return headerCredentials.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    private McpToolImportPolicy importPolicy() {
        if (!readOnly) {
            return new McpToolImportPolicy(
                    allowedTools, Set.of(), effectiveToolNamePrefix(), Map.of(), Map.of(), Map.of(), Map.of());
        }
        Map<String, ToolRisk> risk = new LinkedHashMap<>();
        Map<String, ToolIdempotency> idempotency = new LinkedHashMap<>();
        Map<String, Set<ToolSideEffect>> sideEffects = new LinkedHashMap<>();
        Map<String, ToolApprovalRequirement> approvals = new LinkedHashMap<>();
        for (String tool : allowedTools) {
            risk.put(tool, ToolRisk.LOW);
            idempotency.put(tool, ToolIdempotency.IDEMPOTENT);
            sideEffects.put(tool, Set.of(ToolSideEffect.NETWORK_ACCESS));
            approvals.put(tool, ToolApprovalRequirement.POLICY);
        }
        return new McpToolImportPolicy(
                allowedTools, Set.of(), effectiveToolNamePrefix(), risk, idempotency, sideEffects, approvals);
    }

    /**
     * Credential injections ordered by normalized header name.
     *
     * <p>This order is part of the MCP server binding digest, so it must not depend on map encounter
     * order: the same declaration has to produce the same provider binding reference in every JVM,
     * otherwise a resumed Run rejects its frozen MCP binding as missing or drifted.
     */
    private List<McpCredentialInjection> credentialInjections() {
        return headerCredentials.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new McpCredentialInjection(
                        new CredentialRequirement(entry.getValue().credentialId()),
                        entry.getValue().headerName(),
                        entry.getValue().valuePrefix()))
                .toList();
    }

    private static String validateHeaderName(String headerName) {
        String header = Objects.requireNonNull(headerName, "headerName must not be null")
                .trim();
        if (!header.matches("[A-Za-z][A-Za-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("headerName must be a simple HTTP header name");
        }
        if (RESERVED_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "headerName " + header + " is owned by the HTTP or MCP transport and cannot carry a credential");
        }
        return header;
    }

    private McpServerSpec header(String headerName, String environmentVariableName, String valuePrefix) {
        String header = validateHeaderName(headerName);
        String variable = Objects.requireNonNull(environmentVariableName, "environmentVariableName must not be null")
                .trim();
        if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("environmentVariableName must be a valid environment name");
        }
        Map<String, HeaderCredential> merged = new LinkedHashMap<>(headerCredentials);
        String key = header.toLowerCase(Locale.ROOT);
        merged.put(key, new HeaderCredential("mcp:" + name + ":" + key, header, valuePrefix, variable, null));
        return copy(
                allowedTools,
                toolNamePrefix,
                requirement,
                readOnly,
                connectTimeout,
                requestTimeout,
                allowLoopbackHttp,
                protocolVersion,
                Map.copyOf(merged));
    }

    private McpServerSpec header(String headerName, Supplier<String> valueSupplier, String valuePrefix) {
        String header = validateHeaderName(headerName);
        Objects.requireNonNull(valueSupplier, "valueSupplier must not be null");
        Map<String, HeaderCredential> merged = new LinkedHashMap<>(headerCredentials);
        String key = header.toLowerCase(Locale.ROOT);
        merged.put(key, new HeaderCredential("mcp:" + name + ":" + key, header, valuePrefix, null, valueSupplier));
        return copy(
                allowedTools,
                toolNamePrefix,
                requirement,
                readOnly,
                connectTimeout,
                requestTimeout,
                allowLoopbackHttp,
                protocolVersion,
                Map.copyOf(merged));
    }

    private McpServerSpec withAllowedTools(Set<String> values) {
        return copy(
                values,
                toolNamePrefix,
                requirement,
                readOnly,
                connectTimeout,
                requestTimeout,
                allowLoopbackHttp,
                protocolVersion,
                headerCredentials);
    }

    private McpServerSpec withToolNamePrefix(String value) {
        return copy(
                allowedTools,
                value,
                requirement,
                readOnly,
                connectTimeout,
                requestTimeout,
                allowLoopbackHttp,
                protocolVersion,
                headerCredentials);
    }

    private McpServerSpec withRequirement(McpServerRequirement value) {
        return copy(
                allowedTools,
                toolNamePrefix,
                value,
                readOnly,
                connectTimeout,
                requestTimeout,
                allowLoopbackHttp,
                protocolVersion,
                headerCredentials);
    }

    private McpServerSpec withReadOnly(boolean value) {
        return copy(
                allowedTools,
                toolNamePrefix,
                requirement,
                value,
                connectTimeout,
                requestTimeout,
                allowLoopbackHttp,
                protocolVersion,
                headerCredentials);
    }

    private McpServerSpec withTimeouts(Duration connect, Duration request) {
        return copy(
                allowedTools,
                toolNamePrefix,
                requirement,
                readOnly,
                connect,
                request,
                allowLoopbackHttp,
                protocolVersion,
                headerCredentials);
    }

    private McpServerSpec withLoopbackHttp(boolean value) {
        return copy(
                allowedTools,
                toolNamePrefix,
                requirement,
                readOnly,
                connectTimeout,
                requestTimeout,
                value,
                protocolVersion,
                headerCredentials);
    }

    private McpServerSpec withProtocolVersion(String value) {
        return copy(
                allowedTools,
                toolNamePrefix,
                requirement,
                readOnly,
                connectTimeout,
                requestTimeout,
                allowLoopbackHttp,
                value,
                headerCredentials);
    }

    private McpServerSpec copy(
            Set<String> allowedTools,
            String toolNamePrefix,
            McpServerRequirement requirement,
            boolean readOnly,
            Duration connectTimeout,
            Duration requestTimeout,
            boolean allowLoopbackHttp,
            String protocolVersion,
            Map<String, HeaderCredential> headerCredentials) {
        return new McpServerSpec(
                name,
                endpoint,
                allowedTools,
                toolNamePrefix,
                requirement,
                readOnly,
                connectTimeout,
                requestTimeout,
                allowLoopbackHttp,
                protocolVersion,
                headerCredentials);
    }

    private static String requireConnectionName(String value) {
        String normalized =
                Objects.requireNonNull(value, "name must not be null").trim();
        if (!normalized.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(
                    "MCP connection name must be a lowercase stable identifier such as enterprise-search");
        }
        return normalized;
    }

    /**
     * Resolves the declared prefix, or derives one from the connection name.
     *
     * <p>Derivation is deliberately not attempted in the factory: failing there would leave the caller
     * without an instance to call {@link #toolNamePrefix(String)} on, which made the longer half of the
     * legal connection-name grammar unusable.
     */
    private String effectiveToolNamePrefix() {
        if (toolNamePrefix != null) return toolNamePrefix;
        String candidate = name.replace('-', '_');
        if (!candidate.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new IllegalStateException("MCP connection name " + name
                    + " cannot derive a model-safe Tool name prefix; declare one with toolNamePrefix(...)");
        }
        return candidate;
    }

    private static URI requireEndpoint(URI value) {
        URI endpoint = Objects.requireNonNull(value, "endpoint must not be null");
        // The 2025-line transport routes by origin plus raw path, so a query or fragment here would be
        // silently dropped from every discovery and Tool call instead of reaching the server.
        if (endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "MCP endpoint must not carry a query or fragment; encode per-connection routing in its path");
        }
        return endpoint;
    }

    private static String requireToolNamePrefix(String value) {
        String normalized =
                Objects.requireNonNull(value, "toolNamePrefix must not be null").trim();
        if (!normalized.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new IllegalArgumentException("toolNamePrefix must match [a-z][a-z0-9_]{0,31}");
        }
        return normalized;
    }

    private static String requireRemoteToolName(String value) {
        String normalized = Objects.requireNonNull(value, "remote Tool name must not be null")
                .trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("remote Tool name must not be blank");
        return normalized;
    }

    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    /** One header credential resolved from the environment or a dynamic supplier at assembly and call time. */
    record HeaderCredential(
            String credentialId,
            String headerName,
            String valuePrefix,
            String environmentVariable,
            Supplier<String> valueSupplier) {}
}
