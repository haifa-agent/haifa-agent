package io.haifa.agent.credential.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultSecretRedactorTest {
    @Test
    void fragmentBoundariesNeverExposeCredentialPatternsOrKnownSecrets() {
        var redactor = new DefaultSecretRedactor(List.of("known-token"));
        for (String source : List.of(
                "before Bearer synthetic-fragment-value after",
                "Basic synthetic-basic-value",
                "token synthetic-access-value",
                "Authorization: Bearer\nsynthetic-line-value",
                "client_secret=synthetic-client-value",
                "api_key=synthetic-key-value",
                "password=synthetic-pass-value",
                "https://user:synthetic-password@example.test/path",
                "prefix known-token")) {
            String expected = redactor.redact(source + " ;\n");
            for (int split = 0; split <= source.length(); split++) {
                var stream = redactor.openStream(4096);
                String visible = stream.accept(source.substring(0, split))
                        + stream.accept(source.substring(split))
                        + stream.accept(" ;\n");
                assertThat(visible).isEqualTo(expected);
            }
            var stream = redactor.openStream(4096);
            StringBuilder visible = new StringBuilder();
            source.codePoints().forEach(cp -> visible.append(stream.accept(new String(Character.toChars(cp)))));
            visible.append(stream.accept(" ;\n"));
            assertThat(visible.toString()).isEqualTo(expected);
        }
    }

    @Test
    void fragmentStreamsForwardOrdinaryProgressAndFailClosedAtTheCarryLimit() {
        var redactor = new DefaultSecretRedactor();
        assertThat(redactor.openStream(32).accept("progress before completion"))
                .isEqualTo("progress before completion");
        var stream = redactor.openStream(32);
        assertThat(stream.accept("Bearer ")).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stream.accept("x".repeat(40)))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stream.accept("suffix"))
                .isInstanceOf(IllegalStateException.class);
    }

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

    @Test
    void overlappingConcurrentScopesKeepSecretRedactedUntilLastScopeCloses() throws Exception {
        var redactor = new DefaultSecretRedactor();
        String text = "output with sensitive-concurrent-token-xyz";

        assertThat(redactor.redact(text)).isEqualTo(text);

        AutoCloseable scope1 = redactor.registerScoped("sensitive-concurrent-token-xyz");
        assertThat(redactor.redact(text)).isEqualTo("output with [REDACTED]");

        AutoCloseable scope2 = redactor.registerScoped("sensitive-concurrent-token-xyz");
        assertThat(redactor.redact(text)).isEqualTo("output with [REDACTED]");

        // First scope closes, but scope 2 is still active -> must remain redacted
        scope1.close();
        assertThat(redactor.redact(text)).isEqualTo("output with [REDACTED]");

        // Closing scope1 again is idempotent and does not prematurely decrement
        scope1.close();
        assertThat(redactor.redact(text)).isEqualTo("output with [REDACTED]");

        // Final scope closes -> secret removed
        scope2.close();
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void concurrentOverlappingScopesAreThreadSafe() throws Exception {
        var redactor = new DefaultSecretRedactor();
        String secret = "multithreaded-shared-secret";
        int threads = 10;
        var startGate = new java.util.concurrent.CountDownLatch(1);
        var doneGate = new java.util.concurrent.CountDownLatch(threads);
        var scopes = new java.util.concurrent.CopyOnWriteArrayList<AutoCloseable>();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        scopes.add(redactor.registerScoped(secret));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            doneGate.await();

            assertThat(scopes).hasSize(threads);
            assertThat(redactor.redact("payload: " + secret)).isEqualTo("payload: [REDACTED]");

            for (int i = 0; i < threads - 1; i++) {
                scopes.get(i).close();
                assertThat(redactor.redact("payload: " + secret)).isEqualTo("payload: [REDACTED]");
            }

            scopes.get(threads - 1).close();
            assertThat(redactor.redact("payload: " + secret)).isEqualTo("payload: " + secret);
        } finally {
            executor.shutdownNow();
        }
    }
}
