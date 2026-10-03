package io.haifa.agent.starter;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.mcp.client.McpClientFactory;
import io.haifa.agent.mcp.client.SdkMcpClientFactory;
import io.haifa.agent.sdk.api.HaifaAgentBuilder;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Assembles declared MCP servers into any {@link HaifaAgentBuilder}, including a persistent one.
 *
 * <p>{@link HaifaAgentStarterBuilder} is the process-local quickstart: it fixes in-memory Runtime and
 * Conversation state. A product that already owns its persistence and conversation components, such
 * as a durable SQLite assembly, still needs the same declarative MCP Client. This facade exposes the
 * one missing step without opening the MCP Integration internals:
 *
 * <pre>{@code
 * var mcp = McpToolPlatforms.connect(List.of(search, jobs), tenant, principal);
 * try {
 *     HaifaAgentBuilder builder = HaifaAgents.builder(profile)
 *             .persistence(sqlite.persistence())
 *             .conversation(sqlite.conversation());
 *     mcp.applyTo(builder);
 *     return builder.build();
 * } catch (RuntimeException | Error failure) {
 *     mcp.close();
 *     throw failure;
 * }
 * }</pre>
 *
 * <p>Connection management, Tool discovery, protocol negotiation, schema mapping, credential
 * redaction and resource ownership stay inside the existing MCP Integration. This is the same one
 * implementation path the Starter uses; only the declared servers and the target builder differ.
 */
public final class McpToolPlatforms {
    private McpToolPlatforms() {}

    /**
     * Connects the declared MCP servers and returns the owned contribution for one builder.
     *
     * <p>Credentials are read from the process environment when a spec declares an environment
     * variable. A required server that cannot connect, negotiate or fully resolve fails closed:
     * every connection opened so far is released and the call throws
     * {@link io.haifa.agent.sdk.api.HaifaAgentException}. An optional server that fails contributes
     * no Tool and is reported as a safe diagnostic after {@link McpToolPlatform#applyTo}.
     *
     * <p>An empty list returns an owned no-op: {@code applyTo} adds nothing, so a caller's own
     * credentials and Tool platform are left untouched.
     *
     * @param specs declared MCP servers in declaration order
     * @param tenant trusted tenant used for discovery
     * @param principal trusted principal used for discovery
     * @return the connected MCP contribution; never {@code null}
     */
    public static McpToolPlatform connect(List<McpServerSpec> specs, TenantRef tenant, PrincipalRef principal) {
        return connect(specs, tenant, principal, System::getenv, new SdkMcpClientFactory());
    }

    /**
     * Internal entry shared by the public facade and {@link HaifaAgentStarterBuilder} so MCP is wired
     * through a single implementation path.
     */
    static McpToolPlatform connect(
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
        if (declared.isEmpty()) return NoOpMcpToolPlatform.INSTANCE;
        return NativeMcpToolPlatform.connect(declared, tenant, principal, environment, clientFactory);
    }

    /**
     * One owned MCP contribution with a read-only registered-name snapshot, builder wiring and
     * owned connection cleanup. The underlying platform stays package-private.
     *
     * <p>Call {@link #applyTo} at most once. When applied, the platform registers itself as a managed
     * resource, so an Agent that builds successfully closes it on {@link
     * io.haifa.agent.sdk.api.HaifaAgent#close()}. A caller that abandons the build before the Agent
     * adopts the resource, or whose {@code build()} fails before the resource is collected, must
     * {@link #close()} it explicitly.
     */
    public interface McpToolPlatform extends AutoCloseable {
        /**
         * Returns immutable registered Tool aliases after discovery and allowlist filtering.
         * Reading does not connect, discover, apply or authorize a Tool; the snapshot remains readable after close.
         * Implementations without an authoritative snapshot fail closed.
         */
        default Set<String> toolNames() {
            throw new UnsupportedOperationException("MCP Tool name snapshot is not supported");
        }

        /**
         * Wires Tool registrations, the managed resource, credentials and diagnostics into a builder.
         *
         * @param builder builder that has not applied this platform yet
         * @throws IllegalStateException when called more than once
         */
        void applyTo(HaifaAgentBuilder builder);

        /** Releases every MCP connection and HTTP resource this contribution owns. Idempotent. */
        @Override
        void close();
    }

    /** Owned no-op used when no server is declared; it never touches the target builder. */
    private static final class NoOpMcpToolPlatform implements McpToolPlatform {
        private static final NoOpMcpToolPlatform INSTANCE = new NoOpMcpToolPlatform();

        @Override
        public Set<String> toolNames() {
            return Set.of();
        }

        @Override
        public void applyTo(HaifaAgentBuilder builder) {
            Objects.requireNonNull(builder, "builder must not be null");
        }

        @Override
        public void close() {}
    }
}
