package io.haifa.agent.credential.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CredentialValueObjectTest {
    @Test
    void definitionsAndBindingsDefensivelyCopyMetadataAndAuthorizationSets() {
        var metadata = new HashMap<>(Map.of("issuer", "example"));
        var scopes = new HashSet<>(Set.of("repository:read"));
        var definition = new CredentialDefinition(
                new CredentialDefinitionId("source-token"),
                "source-provider",
                CredentialType.BEARER_TOKEN,
                scopes,
                Set.of(CredentialExposureMode.HTTP_HEADER),
                metadata);
        var binding = new CredentialBinding(
                "binding-1",
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

        metadata.put("secret", "must-not-appear");
        scopes.add("repository:write");

        assertEquals(Map.of("issuer", "example"), definition.metadata());
        assertEquals(Set.of("repository:read"), binding.allowedScopes());
        assertThrows(
                UnsupportedOperationException.class, () -> definition.metadata().put("x", "y"));
        assertFalse(definition.toString().contains("must-not-appear"));
        assertFalse(binding.toString().contains("must-not-appear"));
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
                        Optional.empty(),
                        now,
                        now));
    }
}
