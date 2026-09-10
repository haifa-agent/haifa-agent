package io.haifa.agent.mcp.transport.stdio;

import io.haifa.agent.execution.api.EnvironmentLeaseResolver;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ResolvedExecutionEnvironment;
import io.haifa.agent.mcp.config.McpCredentialInjection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Process-scoped credential bindings materialized only when ExecutionBroker resolves the environment. */
public final class McpStdioEnvironmentRegistry implements EnvironmentLeaseResolver {
    private final Supplier<String> referenceGenerator;
    private final ConcurrentHashMap<String, List<Entry>> bindings = new ConcurrentHashMap<>();

    public McpStdioEnvironmentRegistry(Supplier<String> referenceGenerator) {
        this.referenceGenerator = Objects.requireNonNull(referenceGenerator, "referenceGenerator");
    }

    public Binding bind(
            List<McpCredentialInjection> injections,
            Map<String, String> credentials,
            java.util.Set<String> environmentAllowlist) {
        Objects.requireNonNull(injections, "injections");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(environmentAllowlist, "environmentAllowlist");
        var entries = new java.util.ArrayList<Entry>();
        for (McpCredentialInjection injection : injections) {
            String secret = credentials.get(injection.requirement().credentialId());
            if (secret == null || secret.isBlank()) {
                throw new SecurityException(
                        "MCP stdio credential is missing: " + injection.requirement().credentialId());
            }
            if (!environmentAllowlist.contains(injection.targetName())) {
                throw new SecurityException("MCP stdio credential target is not allowlisted");
            }
            entries.add(new Entry(injection, secret));
        }
        String reference = Objects.requireNonNull(referenceGenerator.get(), "generated reference")
                .trim();
        if (reference.isEmpty()) throw new IllegalStateException("generated environment reference is blank");
        if (bindings.putIfAbsent(reference, List.copyOf(entries)) != null) {
            throw new IllegalStateException("duplicate MCP stdio environment reference");
        }
        return new Binding(new ExecutionEnvironmentRef(List.of(reference)), () -> bindings.remove(reference));
    }

    @Override
    public ResolvedExecutionEnvironment resolve(ExecutionEnvironmentRef reference) {
        var resolved = new LinkedHashMap<String, String>();
        var sensitiveNames = new LinkedHashSet<String>();
        for (String leaseReference : reference.leaseRefs()) {
            List<Entry> entries = bindings.get(leaseReference);
            if (entries == null) throw new SecurityException("MCP stdio environment binding is unavailable");
            for (Entry entry : entries) {
                String value = entry.injection().valuePrefix() + entry.secret();
                String targetName = entry.injection().targetName();
                if (resolved.putIfAbsent(targetName, value) != null) {
                    throw new SecurityException("duplicate MCP stdio environment target");
                }
                sensitiveNames.add(targetName);
            }
        }
        return new ResolvedExecutionEnvironment(resolved, sensitiveNames);
    }

    public record Binding(ExecutionEnvironmentRef reference, AutoCloseable owner) implements AutoCloseable {
        public Binding {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(owner, "owner");
        }

        @Override
        public void close() {
            try {
                owner.close();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException("failed to close MCP stdio environment binding", exception);
            }
        }
    }

    private record Entry(McpCredentialInjection injection, String secret) {}
}
