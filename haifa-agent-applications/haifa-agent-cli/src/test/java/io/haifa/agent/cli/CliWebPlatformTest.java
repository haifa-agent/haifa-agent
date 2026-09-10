package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import org.junit.jupiter.api.Test;

class CliWebPlatformTest {
    @Test
    void assemblesExactProvidersAndConfiguresCredentialSecrets() {
        PrincipalRef principal = new PrincipalRef("local-user", "user");
        CliConfiguration.Web configuration = enabledWeb();
        var platform = CliWebPlatform.create(configuration, principal, name -> switch (name) {
            case "BRAVE_SEARCH_API_KEY" -> "brave-secret";
            case "BROWSERLESS_TOKEN" -> "browserless-secret";
            default -> null;
        });

        assertThat(platform.contributions())
                .extracting(contribution -> contribution.alias().value())
                .containsExactly("web_search", "web_fetch");
        assertThat(platform.contributions())
                .extracting(
                        contribution -> contribution.definition().providerId().value())
                .containsExactly("web-search.brave", "web-fetch.browserless");

        for (var contribution : platform.contributions()) {
            var definition = contribution.definition();
            var requirement = definition.credentialRequirements().getFirst();
            String value = platform.credentialBroker().requireSecret(requirement.credentialId());
            assertThat(value).endsWith("-secret");
        }
    }

    @Test
    void failsBeforeCatalogUseWhenConfiguredCredentialReferenceIsUnavailable() {
        assertThatThrownBy(() ->
                        CliWebPlatform.create(enabledWeb(), new PrincipalRef("local-user", "user"), ignored -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("environment variable is unavailable");
    }

    @Test
    void assemblesTavilyForSearchAndFetchWithSeparateBindings() {
        var configuration = new CliConfiguration.Web(
                new CliConfiguration.WebProvider(
                        true,
                        "tavily",
                        io.haifa.agent.web.provider.TavilyWebSearchProvider.DEFAULT_ENDPOINT,
                        "env://TAVILY_API_KEY",
                        java.time.Duration.ofSeconds(20),
                        1024 * 1024),
                new CliConfiguration.WebProvider(
                        true,
                        "tavily",
                        io.haifa.agent.web.provider.TavilyFetchProvider.DEFAULT_ENDPOINT,
                        "env://TAVILY_API_KEY",
                        java.time.Duration.ofSeconds(20),
                        2 * 1024 * 1024));

        var platform = CliWebPlatform.create(
                configuration, new PrincipalRef("local-user", "user"), ignored -> "tavily-secret");

        assertThat(platform.contributions())
                .extracting(
                        contribution -> contribution.definition().providerId().value())
                .containsExactly("web-search.tavily", "web-fetch.tavily");
    }

    private static CliConfiguration.Web enabledWeb() {
        return new CliConfiguration.Web(
                new CliConfiguration.WebProvider(
                        true,
                        "brave",
                        io.haifa.agent.web.provider.BraveWebSearchProvider.DEFAULT_ENDPOINT,
                        "env://BRAVE_SEARCH_API_KEY",
                        java.time.Duration.ofSeconds(20),
                        1024 * 1024),
                new CliConfiguration.WebProvider(
                        true,
                        "browserless",
                        io.haifa.agent.web.provider.BrowserlessFetchProvider.DEFAULT_ENDPOINT,
                        "env://BROWSERLESS_TOKEN",
                        java.time.Duration.ofSeconds(20),
                        2 * 1024 * 1024));
    }
}
