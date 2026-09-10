package io.haifa.agent.credential.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CredentialValueObjectTest {
    @Test
    void definitionsAndBindingsDefensivelyCopyAuthorizationSets() {
        var scopes = new HashSet<>(Set.of("repository:read"));
        var definition = new CredentialDefinition(
                new CredentialDefinitionId("source-token"), scopes, Set.of(CredentialExposureMode.HTTP_HEADER));
        var binding = new CredentialBinding(
                new TenantRef("tenant"),
                Optional.of(new PrincipalRef("user", "human")),
                definition.id(),
                new CredentialReference("secret-reference-only"),
                new CredentialBindingScope(CredentialScopeKind.USER, "user"),
                Set.of("file.read@1.0.0#provider#hash"),
                Set.of("read"),
                scopes,
                Set.of(CredentialExposureMode.HTTP_HEADER),
                CredentialStatus.ACTIVE,
                Optional.of(Instant.parse("2026-01-01T01:00:00Z")));

        scopes.add("repository:write");

        assertEquals(Set.of("repository:read"), definition.allowedScopes());
        assertEquals(Set.of("repository:read"), binding.allowedScopes());
        assertThrows(
                UnsupportedOperationException.class,
                () -> definition.allowedScopes().add("repository:write"));
        assertFalse(definition.toString().contains("repository:write"));
        assertFalse(binding.toString().contains("repository:write"));
    }

    @Test
    void requestRejectsInvalidLifetime() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertThrows(
                IllegalArgumentException.class,
                () -> new CredentialRequest(
                        new TenantRef("tenant"),
                        new PrincipalRef("user", "human"),
                        new io.haifa.agent.core.run.AgentRunId("run"),
                        "file.read@1.0.0#provider#hash",
                        new CredentialRequirement(
                                new CredentialDefinitionId("source-token"),
                                "read",
                                Set.of("repository:read"),
                                CredentialExposureMode.HTTP_HEADER),
                        java.util.List.of(new CredentialBindingScope(CredentialScopeKind.USER, "user")),
                        now,
                        now));
    }
}
