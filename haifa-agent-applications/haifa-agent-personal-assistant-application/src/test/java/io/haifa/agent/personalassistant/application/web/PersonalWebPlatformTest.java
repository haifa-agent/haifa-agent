package io.haifa.agent.personalassistant.application.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.credential.api.CredentialBindingScope;
import io.haifa.agent.credential.api.CredentialRequest;
import io.haifa.agent.credential.api.CredentialScopeKind;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.core.ToolDefinitionCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PersonalWebPlatformTest {
    private static final TenantRef TENANT = new TenantRef("local");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("personal-user", "user");

    @Test
    void enablesAliyunSearchAndBrowserlessFetchWithSeparateProviderBindings() {
        var platform = PersonalWebPlatform.create(
                TENANT,
                PRINCIPAL,
                provider(
                        true,
                        "aliyun",
                        io.haifa.agent.web.provider.AliyunSearchProvider.DEFAULT_ENDPOINT,
                        "aliyun-secret"),
                provider(
                        true,
                        "browserless",
                        io.haifa.agent.web.provider.BrowserlessFetchProvider.DEFAULT_ENDPOINT,
                        "browserless-secret"),
                new ObjectMapper(),
                Clock.systemUTC());

        assertThat(platform.aliases()).containsExactlyInAnyOrder("web_search", "web_fetch");
        assertThat(platform.contributions())
                .extracting(item -> item.definition().name().value())
                .containsExactly("web.search", "web.fetch");
        assertThat(platform.contributions())
                .extracting(item -> item.definition().providerId().value())
                .containsExactly("web-search.aliyun", "web-fetch.browserless");
        assertThat(platform.contributions())
                .extracting(item -> item.definition()
                        .credentialRequirements()
                        .getFirst()
                        .definitionId()
                        .value())
                .containsExactly("web-search-aliyun", "web-fetch-browserless");
        for (var contribution : platform.contributions()) {
            var definition = contribution.definition();
            var requirement = definition.credentialRequirements().getFirst();
            var coordinate = new ToolCoordinate(
                    definition.name(),
                    definition.version(),
                    definition.providerId(),
                    new ToolDefinitionCanonicalizer().hash(definition));
            Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
            var lease = platform.credential()
                    .broker()
                    .issue(new CredentialRequest(
                            TENANT,
                            PRINCIPAL,
                            new AgentRunId("run-1"),
                            coordinate.externalForm(),
                            requirement,
                            List.of(new CredentialBindingScope(CredentialScopeKind.SYSTEM, "system")),
                            Optional.empty(),
                            now,
                            now.plusSeconds(30)));
            String actual = lease.use(secret -> new String(secret, StandardCharsets.UTF_8));
            String expected = definition.name().value().equals("web.search") ? "aliyun-secret" : "browserless-secret";
            assertThat(actual).isEqualTo(expected);
            lease.close();
        }
        var search = platform.contributions().stream()
                .filter(item -> item.definition().name().value().equals("web.search"))
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
                                "aliyun-secret"),
                        provider(
                                true,
                                "browserless",
                                io.haifa.agent.web.provider.BrowserlessFetchProvider.DEFAULT_ENDPOINT,
                                " "),
                        new ObjectMapper(),
                        Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential is required");
    }

    @Test
    void supportsTavilyForBothSearchAndFetch() {
        var platform = PersonalWebPlatform.create(
                TENANT,
                PRINCIPAL,
                provider(
                        true,
                        "tavily",
                        io.haifa.agent.web.provider.TavilyWebSearchProvider.DEFAULT_ENDPOINT,
                        "tavily-secret"),
                provider(
                        true,
                        "tavily",
                        io.haifa.agent.web.provider.TavilyFetchProvider.DEFAULT_ENDPOINT,
                        "tavily-secret"),
                new ObjectMapper(),
                Clock.systemUTC());

        assertThat(platform.contributions())
                .extracting(item -> item.definition().providerId().value())
                .containsExactly("web-search.tavily", "web-fetch.tavily");
    }

    private static PersonalWebPlatform.ProviderConfiguration provider(
            boolean enabled, String providerId, java.net.URI endpoint, String credential) {
        return new PersonalWebPlatform.ProviderConfiguration(
                enabled, providerId, endpoint, credential, Duration.ofSeconds(30), 2 * 1024 * 1024);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inputProperties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }
}
