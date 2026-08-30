package io.haifa.agent.auth.localmodel.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CodexLocalCompatibilityRegistrationFactoryTest {
    @Test
    void requiresDoubleGateAndNeverSuppliesACompiledClientId() {
        assertThat(CodexLocalCompatibilityRegistrationFactory.create(Map.of())).isEmpty();
        assertThatThrownBy(() -> CodexLocalCompatibilityRegistrationFactory.create(
                        Map.of("HAIFA_CODEX_LOCAL_COMPAT_TEST", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HAIFA_CODEX_OAUTH_CLIENT_ID");

        CodexOAuthClientRegistration registration = CodexLocalCompatibilityRegistrationFactory.create(Map.of(
                        "HAIFA_CODEX_LOCAL_COMPAT_TEST",
                        "true",
                        "HAIFA_CODEX_OAUTH_CLIENT_ID",
                        "externally-supplied-client",
                        "HAIFA_CODEX_ORIGINATOR",
                        "haifa-local"))
                .orElseThrow();
        assertThat(registration.clientId()).isEqualTo("externally-supplied-client");
        assertThat(registration.unofficialLocalCompatibility()).isTrue();
    }
}
