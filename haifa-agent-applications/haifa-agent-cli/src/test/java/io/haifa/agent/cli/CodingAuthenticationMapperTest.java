package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.auth.localmodel.LocalModelAuthReference;
import io.haifa.agent.auth.localmodel.LocalModelConnectionView;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class CodingAuthenticationMapperTest {
    @Test
    void mapsOnlySafeConnectionFields() {
        var shared = new LocalModelConnectionView(
                LocalModelAuthReference.parse("model-auth://openai-codex/default"),
                "openai-codex",
                LocalModelConnectionView.Method.EXTERNAL_LOGIN,
                LocalModelConnectionView.Status.REAUTH_REQUIRED,
                "account-123",
                OptionalLong.of(42),
                Optional.of("AUTH_REAUTH_REQUIRED"),
                true);

        CodingAuthenticationView mapped = new CodingAuthenticationMapper().view(shared);

        assertThat(mapped.connectionId()).isEqualTo("model-auth://openai-codex/default");
        assertThat(mapped.method()).isEqualTo(CodingAuthenticationView.Method.CHATGPT_SUBSCRIPTION);
        assertThat(mapped.status()).isEqualTo(CodingAuthenticationView.Status.REAUTH_REQUIRED);
        assertThat(mapped.unofficialLocalCompatibility()).isTrue();
    }
}
