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

    @Test
    void scopedRegistrationRedactsAndUnregistersOnClose() throws Exception {
        var redactor = new DefaultSecretRedactor();
        String text = "message containing super-sensitive-temp-token-999 and other-temp-key-888";

        assertThat(redactor.redact(text)).isEqualTo(text);

        try (var scope = redactor.registerScoped(List.of("super-sensitive-temp-token-999", "other-temp-key-888"))) {
            assertThat(redactor.redact(text))
                    .doesNotContain("super-sensitive-temp-token-999", "other-temp-key-888")
                    .isEqualTo("message containing [REDACTED] and [REDACTED]");
        }

        // After close, secrets are removed and no longer retained
        assertThat(redactor.redact(text)).isEqualTo(text);
    }
}
