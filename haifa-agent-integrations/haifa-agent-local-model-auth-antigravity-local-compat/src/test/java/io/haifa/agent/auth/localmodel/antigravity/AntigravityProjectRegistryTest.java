package io.haifa.agent.auth.localmodel.antigravity;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.CredentialRef;
import org.junit.jupiter.api.Test;

class AntigravityProjectRegistryTest {
    @Test
    void resolvesOnlyTheProjectAssociatedWithTheTrustedCredentialReference() {
        var registry = new AntigravityProjectRegistry();
        var reference = new CredentialRef("model-auth://google-antigravity/default");

        registry.record(reference, AntigravityProjectAndQuota.of("project-1", "free-tier", 0, 0));

        assertThat(registry.resolve(reference)).contains("project-1");
        assertThat(registry.resolve(new CredentialRef("model-auth://google-antigravity/other")))
                .isEmpty();
    }
}
