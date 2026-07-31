package io.haifa.agent.personalassistant.server.configuration.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.contribution.ShellPlatformContribution;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PersonalModelFactoryTest {
    @Test
    void bindsProviderWithItsAvailableModelList() {
        var source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("provider.id", "deepseek"),
                Map.entry("provider.display-name", "DeepSeek"),
                Map.entry("provider.mode", "remote"),
                Map.entry("provider.endpoint", "https://api.deepseek.com"),
                Map.entry("provider.credential-reference", "env://DEEPSEEK_API_KEY"),
                Map.entry("provider.models[0].id", "deepseek-v4-pro"),
                Map.entry("provider.models[0].display-name", "DeepSeek V4 Pro"),
                Map.entry("provider.models[0].provider-model-id", "deepseek-v4-pro"),
                Map.entry("provider.models[1].id", "deepseek-v4-flash"),
                Map.entry("provider.models[1].display-name", "DeepSeek V4 Flash"),
                Map.entry("provider.models[1].provider-model-id", "deepseek-v4-flash")));

        var provider = new Binder(source)
                .bind("provider", Bindable.of(PersonalAssistantProperties.ModelProvider.class))
                .orElseThrow(() -> new AssertionError("model provider did not bind"));

        assertThat(provider.id()).isEqualTo("deepseek");
        assertThat(provider.models())
                .extracting(PersonalAssistantProperties.ProviderModel::id)
                .containsExactly("deepseek-v4-pro", "deepseek-v4-flash");
    }

    @Test
    void exposesTwoModelsUnderOneProviderAndFreezesBothSnapshots() {
        var provider = new PersonalAssistantProperties.ModelProvider(
                "deepseek",
                "DeepSeek",
                "remote",
                false,
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                List.of(
                        new PersonalAssistantProperties.ProviderModel(
                                "deepseek-v4-pro", "DeepSeek V4 Pro", "deepseek-v4-pro"),
                        new PersonalAssistantProperties.ProviderModel(
                                "deepseek-v4-flash", "DeepSeek V4 Flash", "deepseek-v4-flash")));

        var platform =
                PersonalModelFactory.createPlatform(List.of(provider), "deepseek-v4-pro", new ObjectMapper(), shell());

        assertThat(platform.catalog().available())
                .extracting(model -> model.providerId() + "/" + model.id())
                .containsExactly("deepseek/deepseek-v4-pro", "deepseek/deepseek-v4-flash");
        assertThat(platform.contribution().snapshots()).containsOnlyKeys("deepseek-v4-pro", "deepseek-v4-flash");
        assertThat(platform.contribution().snapshot().modelId().value()).isEqualTo("deepseek-v4-pro");
    }

    private static ShellPlatformContribution shell() {
        return new ShellPlatformContribution(
                new SdkContributionMetadata(
                        new ProductContributionCoordinate("personal-model-test-shell", "1.0.0"),
                        ProductCapabilities.SHELL,
                        SdkConfigurationDigest.sha256("personal-model-test-shell"),
                        ProductProviderSuitability.TEST_ONLY,
                        "Personal model test shell"),
                "UNIX",
                Set.of("bash"));
    }
}
