package io.haifa.agent.credential.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.credential.api.CredentialBinding;
import io.haifa.agent.credential.api.CredentialBindingScope;
import io.haifa.agent.credential.api.CredentialDefinition;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.CredentialOperation;
import io.haifa.agent.credential.api.CredentialOperationRequest;
import io.haifa.agent.credential.api.CredentialReference;
import io.haifa.agent.credential.api.CredentialRequest;
import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.credential.api.CredentialScopeKind;
import io.haifa.agent.credential.api.CredentialStatus;
import io.haifa.agent.credential.api.CredentialStore;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultCredentialBrokerTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void capsLeaseAtBindingExpiryAndRedactsOnlyWhileTheLeaseIsOpen() {
        TenantRef tenant = new TenantRef("tenant");
        PrincipalRef principal = new PrincipalRef("user", "human");
        CredentialDefinitionId definitionId = new CredentialDefinitionId("source-token");
        CredentialReference reference = new CredentialReference("reference-1");
        Instant bindingExpiry = NOW.plusSeconds(10);
        var definition = new CredentialDefinition(
                definitionId, Set.of("repository:read"), Set.of(CredentialExposureMode.HTTP_HEADER));
        var scope = new CredentialBindingScope(CredentialScopeKind.USER, "user");
        var binding = new CredentialBinding(
                "binding",
                tenant,
                Optional.of(principal),
                definitionId,
                reference,
                scope,
                Set.of("file.read@1.0.0#provider#hash"),
                Set.of("read"),
                Set.of("repository:read"),
                Set.of(CredentialExposureMode.HTTP_HEADER),
                CredentialStatus.ACTIVE,
                Optional.of(bindingExpiry));
        var request = new CredentialRequest(
                tenant,
                principal,
                new AgentRunId("run"),
                "file.read@1.0.0#provider#hash",
                new CredentialRequirement(
                        definitionId, "read", Set.of("repository:read"), CredentialExposureMode.HTTP_HEADER),
                List.of(scope),
                NOW,
                NOW.plusSeconds(30));
        var broker = new DefaultCredentialBroker(
                List.of(definition), List.of(binding), new DefaultCredentialResolver(), new RecordingStore(reference));

        CredentialLease lease = broker.issue(request);
        assertThat(lease.expiresAt()).isEqualTo(bindingExpiry);
        assertThat(broker.redactor().redact("plaintext-secret")).isEqualTo("[REDACTED]");
        lease.close();
        lease.close();

        assertThat(lease.isClosed()).isTrue();
        assertThat(broker.redactor().redact("plaintext-secret")).isEqualTo("plaintext-secret");
    }

    @Test
    void issuesControlPlaneCredentialWithoutFabricatingRunOrToolIdentity() {
        TenantRef tenant = new TenantRef("tenant");
        PrincipalRef principal = new PrincipalRef("user", "human");
        CredentialDefinitionId definitionId = new CredentialDefinitionId("mcp-token");
        CredentialReference reference = new CredentialReference("mcp-reference");
        var definition = new CredentialDefinition(
                definitionId, Set.of("mcp:tools:list"), Set.of(CredentialExposureMode.HTTP_HEADER));
        var scope = new CredentialBindingScope(CredentialScopeKind.USER, "user");
        var binding = new CredentialBinding(
                "mcp-binding",
                tenant,
                Optional.of(principal),
                definitionId,
                reference,
                scope,
                Set.of("mcp-server:utility:1:digest"),
                Set.of("discover"),
                Set.of("mcp:tools:list"),
                Set.of(CredentialExposureMode.HTTP_HEADER),
                CredentialStatus.ACTIVE,
                Optional.empty());
        var request = new CredentialOperationRequest(
                CredentialOperation.MCP_DISCOVERY,
                tenant,
                principal,
                "mcp-server:utility:1:digest",
                new CredentialRequirement(
                        definitionId, "discover", Set.of("mcp:tools:list"), CredentialExposureMode.HTTP_HEADER),
                List.of(scope),
                NOW,
                NOW.plusSeconds(30));
        var broker = new DefaultCredentialBroker(
                List.of(definition), List.of(binding), new DefaultCredentialResolver(), new RecordingStore(reference));

        CredentialLease lease = broker.issue(request);
        try {
            assertThat(lease.reference()).isEqualTo(reference);
            assertThat(lease.isClosed()).isFalse();
        } finally {
            lease.close();
        }

        List<String> components = Arrays.stream(CredentialOperationRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(components).doesNotContain("runId", "toolCoordinate", "explicitBindingId");
        assertThat(components).contains("operation", "targetBindingReference");
    }

    private static final class RecordingStore implements CredentialStore {
        private final CredentialReference reference;

        private RecordingStore(CredentialReference reference) {
            this.reference = reference;
        }

        @Override
        public void store(
                CredentialReference reference, TenantRef tenant, CredentialDefinitionId definitionId, byte[] secret) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CredentialLease lease(
                CredentialReference reference,
                TenantRef tenant,
                CredentialDefinitionId definitionId,
                Instant expiresAt) {
            return new CredentialLease() {
                private boolean closed;

                @Override
                public CredentialReference reference() {
                    return RecordingStore.this.reference;
                }

                @Override
                public Instant expiresAt() {
                    return expiresAt;
                }

                @Override
                public boolean isClosed() {
                    return closed;
                }

                @Override
                public <T> T use(io.haifa.agent.credential.api.SecretFunction<T> action) {
                    if (closed) throw new IllegalStateException("closed");
                    return action.apply("plaintext-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }
    }
}
