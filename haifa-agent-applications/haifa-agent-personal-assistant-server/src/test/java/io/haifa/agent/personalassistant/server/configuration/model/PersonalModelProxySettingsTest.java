package io.haifa.agent.personalassistant.server.configuration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalModelProxySettingsTest {
    @TempDir
    Path directory;

    @Test
    void persistsOneCustomHttpProxyForProvidersSharingTheSameEndpointOrigin() {
        var settings = new PersonalModelProxySettings(
                List.of(provider("first", "https://example.com/v1"), provider("second", "https://example.com/v2")),
                directory,
                new ObjectMapper());

        settings.saveCustom("first", URI.create("http://127.0.0.1:2081"));

        assertThat(settings.mode("first")).isEqualTo("CUSTOM");
        assertThat(settings.mode("second")).isEqualTo("CUSTOM");
        assertThat(settings.proxyFor(URI.create("https://example.com/v1/chat/completions")))
                .contains(URI.create("http://127.0.0.1:2081"));

        var reopened = new PersonalModelProxySettings(
                List.of(provider("first", "https://example.com/v1"), provider("second", "https://example.com/v2")),
                directory,
                new ObjectMapper());
        assertThat(reopened.mode("second")).isEqualTo("CUSTOM");

        reopened.resetToSystem("second");
        assertThat(reopened.mode("first")).isEqualTo("SYSTEM");
    }

    @Test
    void rejectsHttpsAndCredentialBearingProxyOrigins() {
        var settings = new PersonalModelProxySettings(
                List.of(provider("provider", "https://example.com/v1")), directory, new ObjectMapper());

        assertThatThrownBy(() -> settings.saveCustom("provider", URI.create("https://127.0.0.1:2081")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_PROXY_INVALID");
        assertThatThrownBy(() -> settings.saveCustom("provider", URI.create("http://user:secret@127.0.0.1:2081")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_PROXY_INVALID");
    }

    private static PersonalAssistantProperties.ModelProvider provider(String id, String endpoint) {
        return new PersonalAssistantProperties.ModelProvider(
                id,
                id,
                "remote",
                false,
                true,
                URI.create(endpoint),
                "env://TEST_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding("openai-chat-completions", "standard", null)),
                List.of(new PersonalAssistantProperties.ProviderModel(
                        id + "-model",
                        id,
                        id,
                        id,
                        "openai-chat-completions",
                        Set.of(ModelCapability.TEXT_CHAT),
                        ModelReasoningMode.DISABLED,
                        128_000,
                        8_192)),
                null);
    }
}
