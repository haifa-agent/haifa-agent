package io.haifa.agent.auth.localmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExternalLoginRegistryTest {
    @Test
    void supportsASecondDriverWithoutRegistryChanges() {
        ExternalLoginMethod first = new NoopMethod("openai-codex");
        ExternalLoginMethod second = new NoopMethod("future-login");
        ExternalLoginRegistry registry = new ExternalLoginRegistry(List.of(first, second));

        assertThat(registry.require(new ExternalLoginMethodId("future-login"))).isSameAs(second);
        assertThat(registry.descriptors())
                .extracting(value -> value.methodId().value())
                .containsExactlyInAnyOrder("openai-codex", "future-login");
        assertThatThrownBy(() -> registry.descriptors().add(first.descriptor()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicateAndUnknownMethods() {
        assertThatThrownBy(() -> new ExternalLoginRegistry(
                        List.of(new NoopMethod("openai-codex"), new NoopMethod("openai-codex"))))
                .isInstanceOf(IllegalArgumentException.class);
        ExternalLoginRegistry registry = new ExternalLoginRegistry(List.of());
        assertThatThrownBy(() -> registry.require(new ExternalLoginMethodId("unknown")))
                .isInstanceOf(ExternalLoginMethodUnavailableException.class)
                .hasMessage("AUTH_LOGIN_METHOD_UNAVAILABLE");
    }

    private record NoopMethod(ExternalLoginMethodDescriptor descriptor) implements ExternalLoginMethod {
        private NoopMethod(String id) {
            this(new ExternalLoginMethodDescriptor(
                    new ExternalLoginMethodId(id), id, Set.of(ExternalLoginMode.BROWSER), false, Optional.empty()));
        }

        @Override
        public ExternalLoginOperation create(ExternalLoginMode mode, ExternalLoginOperationContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredExternalCredential refresh(StoredExternalCredential credential, Instant refreshBefore) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revoke(StoredExternalCredential credential) {}
    }
}
