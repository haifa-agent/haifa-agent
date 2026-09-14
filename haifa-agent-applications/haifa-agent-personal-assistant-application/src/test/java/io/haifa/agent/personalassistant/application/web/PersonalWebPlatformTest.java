package io.haifa.agent.personalassistant.application.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PersonalWebPlatformTest {
    private static final TenantRef TENANT = new TenantRef("local");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("personal-user", "user");

    @Test
    void enablesAliyunSearchAndBrowserlessFetchWithSeparateProviderBindings() {
        Map<String, String> env = Map.of(
                "ALIYUN_KEY", "aliyun-secret",
                "BROWSERLESS_KEY", "browserless-secret");
        var platform = PersonalWebPlatform.create(
                TENANT,
                PRINCIPAL,
                provider(
                        true,
                        "aliyun",
                        io.haifa.agent.web.provider.AliyunSearchProvider.DEFAULT_ENDPOINT,
                        "env://ALIYUN_KEY"),
                provider(
                        true,
                        "browserless",
                        io.haifa.agent.web.provider.BrowserlessFetchProvider.DEFAULT_ENDPOINT,
                        "env://BROWSERLESS_KEY"),
                new ObjectMapper(),
                Clock.systemUTC(),
                env::get,
                name -> java.util.Optional.empty());

        assertThat(platform.aliases()).containsExactlyInAnyOrder("web_search", "web_fetch");
        assertThat(platform.contributions())
                .extracting(item -> item.definition().name().value())
                .containsExactly("web_search", "web_fetch");
        assertThat(platform.contributions())
                .extracting(item -> item.definition().providerId().value())
                .containsExactly("web-search.aliyun", "web-fetch.browserless");
        assertThat(platform.contributions())
                .extracting(item ->
                        item.definition().credentialRequirements().getFirst().credentialId())
                .containsExactly("web-search-aliyun", "web-fetch-browserless");
        for (var contribution : platform.contributions()) {
            var definition = contribution.definition();
            var requirement = definition.credentialRequirements().getFirst();
            String actual = platform.credential().broker().requireSecret(requirement.credentialId());
            String expected = definition.name().value().equals("web_search") ? "aliyun-secret" : "browserless-secret";
            assertThat(actual).isEqualTo(expected);
        }
        var search = platform.contributions().stream()
                .filter(item -> item.definition().name().value().equals("web_search"))
                .findFirst()
                .orElseThrow();
        assertThat(inputProperties(search.definition().inputSchema().document()))
                .containsOnlyKeys("query", "maxResults", "freshness", "includeDomains", "excludeDomains");
    }

    @Test
    void disabledPlatformPublishesNoWebToolAliases() {
        var platform = PersonalWebPlatform.create(
                TENANT,
                PRINCIPAL,
                provider(false, "aliyun", io.haifa.agent.web.provider.AliyunSearchProvider.DEFAULT_ENDPOINT, ""),
                provider(
                        false,
                        "browserless",
                        io.haifa.agent.web.provider.BrowserlessFetchProvider.DEFAULT_ENDPOINT,
                        ""),
                new ObjectMapper(),
                Clock.systemUTC());

        assertThat(platform.contributions()).isEmpty();
        assertThat(platform.aliases()).isEmpty();
    }

    @Test
    void enabledProviderRejectsItsOwnMissingCredential() {
        assertThatThrownBy(() -> PersonalWebPlatform.create(
                        TENANT,
                        PRINCIPAL,
                        provider(
                                true,
                                "aliyun",
                                io.haifa.agent.web.provider.AliyunSearchProvider.DEFAULT_ENDPOINT,
                                "env://ALIYUN_KEY"),
                        provider(
                                true,
                                "browserless",
                                io.haifa.agent.web.provider.BrowserlessFetchProvider.DEFAULT_ENDPOINT,
                                " "),
                        new ObjectMapper(),
                        Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentialReference is required");
    }

    @Test
    void enabledProviderRejectsPlaintextCredentialReference() {
        assertThatThrownBy(() -> provider(
                        true,
                        "aliyun",
                        io.haifa.agent.web.provider.AliyunSearchProvider.DEFAULT_ENDPOINT,
                        "aliyun-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentialReference must use env:// or os://");
    }

    @Test
    void supportsTavilyForBothSearchAndFetch() {
        Map<String, String> env = Map.of("TAVILY_KEY", "tavily-secret");
        var platform = PersonalWebPlatform.create(
                TENANT,
                PRINCIPAL,
                provider(
                        true,
                        "tavily",
                        io.haifa.agent.web.provider.TavilyWebSearchProvider.DEFAULT_ENDPOINT,
                        "env://TAVILY_KEY"),
                provider(
                        true,
                        "tavily",
                        io.haifa.agent.web.provider.TavilyFetchProvider.DEFAULT_ENDPOINT,
                        "env://TAVILY_KEY"),
                new ObjectMapper(),
                Clock.systemUTC(),
                env::get,
                name -> java.util.Optional.empty());

        assertThat(platform.contributions())
                .extracting(item -> item.definition().providerId().value())
                .containsExactly("web-search.tavily", "web-fetch.tavily");
    }

    @Test
    void resolvesCredentialsOnDemandFromEnvAndOsStore() {
        Map<String, String> env = Map.of("MY_SEARCH_KEY", "resolved-env-secret");
        Map<String, String> os = Map.of("my_fetch_key", "resolved-os-secret");

        var platform = PersonalWebPlatform.create(
                TENANT,
                PRINCIPAL,
                provider(
                        true,
                        "aliyun",
                        io.haifa.agent.web.provider.AliyunSearchProvider.DEFAULT_ENDPOINT,
                        "env://MY_SEARCH_KEY"),
                provider(
                        true,
                        "browserless",
                        io.haifa.agent.web.provider.BrowserlessFetchProvider.DEFAULT_ENDPOINT,
                        "os://my_fetch_key"),
                new ObjectMapper(),
                Clock.systemUTC(),
                env::get,
                name -> java.util.Optional.ofNullable(os.get(name)));

        assertThat(platform.credential().broker().requireSecret("web-search-aliyun"))
                .isEqualTo("resolved-env-secret");
        assertThat(platform.credential().broker().requireSecret("web-fetch-browserless"))
                .isEqualTo("resolved-os-secret");
    }

    private static PersonalWebPlatform.ProviderConfiguration provider(
            boolean enabled, String providerId, java.net.URI endpoint, String credentialReference) {
        return new PersonalWebPlatform.ProviderConfiguration(
                enabled, providerId, endpoint, credentialReference, Duration.ofSeconds(30), 2 * 1024 * 1024);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inputProperties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }
}
