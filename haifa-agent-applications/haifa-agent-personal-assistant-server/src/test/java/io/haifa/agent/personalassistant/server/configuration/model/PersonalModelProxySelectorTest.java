package io.haifa.agent.personalassistant.server.configuration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PersonalModelProxySelectorTest {
    private static final ProxySelector DIRECT_SELECTOR = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException failure) {}
    };

    @Test
    void routesOnlyTheConfiguredProviderOriginThroughItsHttpProxy() {
        var selector = PersonalModelProxySelector.from(
                List.of(
                        provider("codex", "https://chatgpt.com/backend-api/codex", "http://127.0.0.1:2081"),
                        provider("deepseek", "https://api.deepseek.com", null)),
                DIRECT_SELECTOR);

        Proxy codex = selector.select(URI.create("https://chatgpt.com/backend-api/codex/responses"))
                .getFirst();
        assertThat(codex.type()).isEqualTo(Proxy.Type.HTTP);
        assertThat((InetSocketAddress) codex.address())
                .extracting(InetSocketAddress::getHostString, InetSocketAddress::getPort)
                .containsExactly("127.0.0.1", 2081);

        assertThat(selector.select(URI.create("https://api.deepseek.com/chat/completions")))
                .isNotEmpty()
                .allMatch(proxy -> proxy.type() == Proxy.Type.DIRECT);
    }

    @Test
    void rejectsAmbiguousProxyRoutesForTheSameEndpointOrigin() {
        assertThatThrownBy(() -> PersonalModelProxySelector.from(
                        List.of(
                                provider("first", "https://example.com/v1", "http://127.0.0.1:2081"),
                                provider("second", "https://example.com/v2", null)),
                        DIRECT_SELECTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same proxy configuration");
    }

    @Test
    void rejectsProxyCredentialsAndNonHttpSchemes() {
        assertThatThrownBy(() -> provider("codex", "https://chatgpt.com/backend-api/codex", "socks5://127.0.0.1:2080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP proxy origin");
        assertThatThrownBy(() ->
                        provider("codex", "https://chatgpt.com/backend-api/codex", "http://user:secret@127.0.0.1:2081"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP proxy origin");
    }

    private static PersonalAssistantProperties.ModelProvider provider(String id, String endpoint, String proxy) {
        return new PersonalAssistantProperties.ModelProvider(
                id,
                id,
                "remote",
                false,
                true,
                URI.create(endpoint),
                "env://TEST_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding("openai-responses", "standard", null)),
                List.of(new PersonalAssistantProperties.ProviderModel(
                        id + "-model",
                        id,
                        id,
                        id,
                        "openai-responses",
                        Set.of(ModelCapability.TEXT_CHAT),
                        ModelReasoningMode.DISABLED,
                        128_000,
                        8_192)),
                proxy == null ? null : URI.create(proxy));
    }
}
