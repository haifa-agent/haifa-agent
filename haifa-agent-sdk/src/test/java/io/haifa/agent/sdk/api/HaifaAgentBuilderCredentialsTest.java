package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.sdk.contribution.CredentialPlatformContribution;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The builder holds one credential broker and refuses to replace it silently. */
class HaifaAgentBuilderCredentialsTest {

    @Test
    void acceptsTheSameContributionAgain() {
        var credentials = contribution();

        assertThatCode(() -> HaifaAgents.builder().credentials(credentials).credentials(credentials))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsADifferentContributionInEitherOrder() {
        var first = contribution();
        var second = contribution();

        assertThatThrownBy(() -> HaifaAgents.builder().credentials(first).credentials(second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credential contribution is already set");
        assertThatThrownBy(() -> HaifaAgents.builder().credentials(second).credentials(first))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credential contribution is already set");
    }

    @Test
    void acceptsANewWrapperAroundTheSameBroker() {
        CredentialBroker broker = id -> Optional.empty();

        assertThatCode(() -> HaifaAgents.builder()
                        .credentials(new CredentialPlatformContribution(broker))
                        .credentials(new CredentialPlatformContribution(broker)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> HaifaAgents.builder().credentials(null)).isInstanceOf(NullPointerException.class);
    }

    private static CredentialPlatformContribution contribution() {
        // A non-capturing lambda is one shared instance per call site; use a distinct broker per call.
        CredentialBroker broker = new CredentialBroker() {
            @Override
            public Optional<String> getSecret(String credentialId) {
                return Optional.empty();
            }
        };
        return new CredentialPlatformContribution(broker);
    }
}
