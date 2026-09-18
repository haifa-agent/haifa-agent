package io.haifa.agent.auth.localmodel.antigravity;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class AntigravityPkceTest {
    @Test
    void generatesValidPkceMaterialAndChallenge() {
        AntigravityPkce pkce = new AntigravityPkce(new SecureRandom());
        String verifier = pkce.verifier();
        String state = pkce.state();
        String challenge = AntigravityPkce.challenge(verifier);

        assertThat(verifier).isNotBlank().matches("[A-Za-z0-9_-]+");
        assertThat(state).isNotBlank().matches("[A-Za-z0-9_-]+");
        assertThat(challenge).isNotBlank().matches("[A-Za-z0-9_-]+");
        // Challenge is deterministic for the same verifier
        assertThat(AntigravityPkce.challenge(verifier)).isEqualTo(challenge);
    }
}
