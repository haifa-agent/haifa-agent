package io.haifa.agent.credential.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.credential.api.CredentialBinding;
import io.haifa.agent.credential.api.CredentialBindingScope;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialException;
import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialReference;
import io.haifa.agent.credential.api.CredentialRequest;
import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.credential.api.CredentialScopeKind;
import io.haifa.agent.credential.api.CredentialStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultCredentialResolverTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("user", "human");
    private static final CredentialDefinitionId DEFINITION = new CredentialDefinitionId("token");
    private static final CredentialBindingScope PROJECT =
            new CredentialBindingScope(CredentialScopeKind.PROJECT, "project-1");
    private static final CredentialBindingScope USER = new CredentialBindingScope(CredentialScopeKind.USER, "user");

    @Test
    void resolvesByFixedPrecedenceWithoutExpandingAuthorization() {
        CredentialBinding project = binding("project", PROJECT, PRINCIPAL);
        CredentialBinding user = binding("user", USER, PRINCIPAL);

        CredentialBinding resolved = new DefaultCredentialResolver().resolve(request(), List.of(user, project));

        assertThat(resolved.bindingId()).isEqualTo("project");
        assertThatThrownBy(() -> new DefaultCredentialResolver()
                        .resolve(request(), List.of(binding("other", PROJECT, new PrincipalRef("other", "human")))))
                .isInstanceOf(CredentialException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void failsClosedOnSamePriorityAmbiguity() {
        assertThatThrownBy(() -> new DefaultCredentialResolver()
                        .resolve(
                                request(),
                                List.of(binding("one", PROJECT, PRINCIPAL), binding("two", PROJECT, PRINCIPAL))))
                .isInstanceOf(CredentialException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void rejectsUnavailableDisabledExpiredAndOutOfScopeBindings() {
        assertUnavailable(binding("disabled", PROJECT, PRINCIPAL, CredentialStatus.DISABLED, NOW.plusSeconds(60)));
        assertUnavailable(binding("expired", PROJECT, PRINCIPAL, CredentialStatus.ACTIVE, NOW));
        assertUnavailable(new CredentialBinding(
                "wrong-scope",
                TENANT,
                Optional.of(PRINCIPAL),
                DEFINITION,
                new CredentialReference("wrong-scope"),
                new CredentialBindingScope(CredentialScopeKind.SESSION, "other-session"),
                Set.of("file.read@1.0.0#provider#hash"),
                Set.of("read"),
                Set.of("repository:read"),
                Set.of(CredentialExposureMode.HTTP_HEADER),
                CredentialStatus.ACTIVE,
                Optional.of(NOW.plusSeconds(60))));
        assertUnavailable(new CredentialBinding(
                "wrong-capability",
                TENANT,
                Optional.of(PRINCIPAL),
                DEFINITION,
                new CredentialReference("wrong-capability"),
                PROJECT,
                Set.of("file.write@1.0.0#provider#hash"),
                Set.of("write"),
                Set.of("repository:write"),
                Set.of(CredentialExposureMode.ENVIRONMENT_VARIABLE),
                CredentialStatus.ACTIVE,
                Optional.of(NOW.plusSeconds(60))));
    }

    private static void assertUnavailable(CredentialBinding binding) {
        assertThatThrownBy(() -> new DefaultCredentialResolver().resolve(request(), List.of(binding)))
                .isInstanceOf(CredentialException.class)
                .hasMessageContaining("unavailable");
    }

    private static CredentialRequest request() {
        return new CredentialRequest(
                TENANT,
                PRINCIPAL,
                new AgentRunId("run"),
                "file.read@1.0.0#provider#hash",
                new CredentialRequirement(
                        DEFINITION, "read", Set.of("repository:read"), CredentialExposureMode.HTTP_HEADER),
                List.of(PROJECT, USER),
                Optional.empty(),
                NOW,
                NOW.plusSeconds(30));
    }

    private static CredentialBinding binding(String id, CredentialBindingScope scope, PrincipalRef principal) {
        return binding(id, scope, principal, CredentialStatus.ACTIVE, NOW.plusSeconds(60));
    }

    private static CredentialBinding binding(
            String id,
            CredentialBindingScope scope,
            PrincipalRef principal,
            CredentialStatus status,
            Instant expiresAt) {
        return new CredentialBinding(
                id,
                TENANT,
                Optional.of(principal),
                DEFINITION,
                new CredentialReference(id),
                scope,
                Set.of("file.read@1.0.0#provider#hash"),
                Set.of("read"),
                Set.of("repository:read"),
                Set.of(CredentialExposureMode.HTTP_HEADER),
                status,
                Optional.of(expiresAt));
    }
}
