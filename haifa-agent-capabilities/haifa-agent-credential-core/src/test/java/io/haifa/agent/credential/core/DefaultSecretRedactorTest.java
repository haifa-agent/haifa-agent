package io.haifa.agent.credential.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultSecretRedactorTest {
    @Test
    void redactsKnownSecretsAndCommonAuthorizationPatterns() {
        var redactor = new DefaultSecretRedactor(List.of("exact-secret"));

        assertThat(redactor.redact("token=exact-secret Authorization: Bearer other-secret api_key=third-secret "
                        + "https://user:remote-secret@github.example/repo.git"))
                .doesNotContain("exact-secret", "other-secret", "third-secret", "remote-secret")
                .contains("[REDACTED]");
    }

    @Test
    void returnsBlankOrNullUnchanged() {
        var redactor = new DefaultSecretRedactor(List.of("secret"));
        assertThat(redactor.redact(null)).isNull();
        assertThat(redactor.redact("")).isEmpty();
        assertThat(redactor.redact("   ")).isEqualTo("   ");
    }
}
