package io.haifa.agent.mcp.transport.stdio;

import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.execution.api.EnvironmentLeaseResolver;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.mcp.config.McpCredentialInjection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
            List<CredentialLease> credentials,
            java.util.Set<String> environmentAllowlist) {
        Objects.requireNonNull(injections, "injections");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(environmentAllowlist, "environmentAllowlist");
        if (injections.size() != credentials.size()) {
            throw new SecurityException("MCP stdio credential lease set is incomplete");
        }
        var entries = new java.util.ArrayList<Entry>();
        for (int index = 0; index < injections.size(); index++) {
            McpCredentialInjection injection = injections.get(index);
            if (injection.requirement().exposureMode() != CredentialExposureMode.ENVIRONMENT_VARIABLE) {
                throw new SecurityException("non-environment credential cannot be injected into MCP stdio");
            }
            if (!environmentAllowlist.contains(injection.targetName())) {
                throw new SecurityException("MCP stdio credential target is not allowlisted");
            }
            entries.add(new Entry(injection, credentials.get(index)));
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
    public Map<String, String> resolve(ExecutionEnvironmentRef reference) {
        var resolved = new LinkedHashMap<String, String>();
        for (String leaseReference : reference.leaseRefs()) {
            List<Entry> entries = bindings.get(leaseReference);
            if (entries == null) throw new SecurityException("MCP stdio environment binding is unavailable");
            for (Entry entry : entries) {
                String value = entry.lease()
                        .use(secret -> entry.injection().valuePrefix() + new String(secret, StandardCharsets.UTF_8));
                if (resolved.putIfAbsent(entry.injection().targetName(), value) != null) {
                    throw new SecurityException("duplicate MCP stdio environment target");
                }
            }
        }
        return Map.copyOf(resolved);
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

    private record Entry(McpCredentialInjection injection, CredentialLease lease) {}
}
