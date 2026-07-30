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
    void enablesAliyunSearchAndFetchFromOneProductCredential() {
        var platform = PersonalWebPlatform.create(
                TENANT,
                PRINCIPAL,
                true,
                "test-secret",
                Duration.ofSeconds(30),
                2 * 1024 * 1024,
                4 * 1024 * 1024,
                new ObjectMapper(),
                Clock.systemUTC());

        assertThat(platform.aliases()).containsExactlyInAnyOrder("web_search", "web_fetch");
        assertThat(platform.contributions())
                .extracting(item -> item.definition().name().value())
                .containsExactly("web.search", "web.fetch");
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
                false,
                "",
                Duration.ofSeconds(30),
                2 * 1024 * 1024,
                4 * 1024 * 1024,
                new ObjectMapper(),
                Clock.systemUTC());

        assertThat(platform.contributions()).isEmpty();
        assertThat(platform.aliases()).isEmpty();
    }

    @Test
    void enabledPlatformRejectsMissingCredential() {
        assertThatThrownBy(() -> PersonalWebPlatform.create(
                        TENANT,
                        PRINCIPAL,
                        true,
                        " ",
                        Duration.ofSeconds(30),
                        2 * 1024 * 1024,
                        4 * 1024 * 1024,
                        new ObjectMapper(),
                        Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Aliyun IQS credential");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inputProperties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }
}
