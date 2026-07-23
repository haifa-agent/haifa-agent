package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.credential.api.CredentialBindingScope;
import io.haifa.agent.credential.api.CredentialRequest;
import io.haifa.agent.credential.api.CredentialScopeKind;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.core.ToolDefinitionCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CliWebPlatformTest {
    @Test
    void assemblesExactProvidersAndIssuesInvocationScopedCredentialLeases() {
        PrincipalRef principal = new PrincipalRef("local-user", "user");
        CliConfiguration.Web configuration = enabledWeb();
        var platform = CliWebPlatform.create(configuration, principal, name -> switch (name) {
            case "BRAVE_SEARCH_API_KEY" -> "brave-secret";
            case "ALIYUN_IQS_API_KEY" -> "aliyun-secret";
            default -> null;
        });

        assertThat(platform.contributions())
                .extracting(contribution -> contribution.alias().value())
                .containsExactly("web_search", "web_fetch");
        assertThat(platform.contributions())
                .extracting(
                        contribution -> contribution.definition().providerId().value())
                .containsExactly("web-search.brave", "web-fetch.aliyun");

        for (var contribution : platform.contributions()) {
            var definition = contribution.definition();
            var requirement = definition.credentialRequirements().getFirst();
            var coordinate = new ToolCoordinate(
                    definition.name(),
                    definition.version(),
                    definition.providerId(),
                    new ToolDefinitionCanonicalizer().hash(definition));
            Instant now = Instant.now();
            var lease = platform.credentialBroker()
                    .issue(new CredentialRequest(
                            new TenantRef("local"),
                            principal,
                            new AgentRunId("run-1"),
                            coordinate.externalForm(),
                            requirement,
                            List.of(new CredentialBindingScope(CredentialScopeKind.SYSTEM, "system")),
                            Optional.empty(),
                            now,
                            now.plusSeconds(30)));
            String value = lease.use(secret -> new String(secret, StandardCharsets.UTF_8));
            assertThat(value).endsWith("-secret");
            assertThat(lease.reference().value()).doesNotContain(value);
            lease.close();
        }
    }

    @Test
    void failsBeforeCatalogUseWhenConfiguredCredentialReferenceIsUnavailable() {
        assertThatThrownBy(() ->
                        CliWebPlatform.create(enabledWeb(), new PrincipalRef("local-user", "user"), ignored -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("environment variable is unavailable");
    }

    private static CliConfiguration.Web enabledWeb() {
        return new CliConfiguration.Web(
                new CliConfiguration.WebProvider(
                        true,
                        "brave",
                        io.haifa.agent.application.project.tool.web.provider.BraveWebSearchProvider.DEFAULT_ENDPOINT,
                        "env://BRAVE_SEARCH_API_KEY",
                        java.time.Duration.ofSeconds(20),
                        1024 * 1024),
                new CliConfiguration.WebProvider(
                        true,
                        "aliyun",
                        io.haifa.agent.application.project.tool.web.provider.AliyunFetchProvider.DEFAULT_ENDPOINT,
                        "env://ALIYUN_IQS_API_KEY",
                        java.time.Duration.ofSeconds(20),
                        2 * 1024 * 1024));
    }
}
