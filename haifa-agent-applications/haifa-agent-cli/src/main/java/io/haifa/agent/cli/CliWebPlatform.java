package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.credential.core.DefaultCredentialBroker;
import io.haifa.agent.web.DefaultWebUrlPolicy;
import io.haifa.agent.web.WebFetchProvider;
import io.haifa.agent.web.WebFetchProviderRegistry;
import io.haifa.agent.web.WebProviderId;
import io.haifa.agent.web.WebSearchProvider;
import io.haifa.agent.web.WebSearchProviderRegistry;
import io.haifa.agent.web.WebToolCatalog;
import io.haifa.agent.web.WebToolCatalogContribution;
import io.haifa.agent.web.provider.AliyunFetchProvider;
import io.haifa.agent.web.provider.AliyunSearchProvider;
import io.haifa.agent.web.provider.BraveWebSearchProvider;
import io.haifa.agent.web.provider.BrowserlessFetchProvider;
import io.haifa.agent.web.provider.TavilyFetchProvider;
import io.haifa.agent.web.provider.TavilyWebSearchProvider;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Product-level Web provider selection and Credential Broker assembly for the local CLI profile. */
final class CliWebPlatform {
    private final List<WebToolCatalogContribution> contributions;
    private final DefaultCredentialBroker credentialBroker;

    private CliWebPlatform(List<WebToolCatalogContribution> contributions, DefaultCredentialBroker credentialBroker) {
        this.contributions = List.copyOf(contributions);
        this.credentialBroker = credentialBroker;
    }

    static CliWebPlatform create(CliConfiguration.Web configuration, PrincipalRef principal) {
        return create(configuration, principal, System::getenv);
    }

    static CliWebPlatform create(
            CliConfiguration.Web configuration, PrincipalRef principal, Function<String, String> environment) {
        var client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var mapper = new ObjectMapper();
        Clock clock = Clock.systemUTC();
        var toolCatalog = new WebToolCatalog();
        List<WebToolCatalogContribution> contributions = new ArrayList<>();

        if (configuration.search().enabled()) {
            WebSearchProvider selected = new WebSearchProviderRegistry(
                            List.of(searchProvider(configuration.search(), client, mapper, clock)))
                    .require(new WebProviderId(configuration.search().providerId()));
            contributions.add(toolCatalog.search(selected));
        }
        if (configuration.fetch().enabled()) {
            WebFetchProvider selected = new WebFetchProviderRegistry(
                            List.of(fetchProvider(configuration.fetch(), client, mapper, clock)))
                    .require(new WebProviderId(configuration.fetch().providerId()));
            contributions.add(toolCatalog.fetch(selected, new DefaultWebUrlPolicy()));
        }
        if (contributions.isEmpty()) {
            return new CliWebPlatform(List.of(), new DefaultCredentialBroker(Map.of()));
        }

        Map<String, String> secrets = new HashMap<>();
        for (WebToolCatalogContribution contribution : contributions) {
            var requirement = contribution.definition().credentialRequirements().stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Web provider credential requirement is missing"));
            String operation = contribution.definition().name().value();
            CliConfiguration.WebProvider providerConfiguration =
                    operation.equals("web.search") ? configuration.search() : configuration.fetch();
            String environmentName = providerConfiguration.credentialRef().substring("env://".length());
            String secret = environment.apply(environmentName);
            if (secret == null || secret.isBlank()) {
                throw new IllegalArgumentException(
                        "Web credential environment variable is unavailable: " + environmentName);
            }
            secrets.put(requirement.credentialId(), secret);
        }
        var broker = new DefaultCredentialBroker(secrets);
        return new CliWebPlatform(contributions, broker);
    }

    List<WebToolCatalogContribution> contributions() {
        return contributions;
    }

    DefaultCredentialBroker credentialBroker() {
        return credentialBroker;
    }

    private static WebSearchProvider searchProvider(
            CliConfiguration.WebProvider configuration, HttpClient client, ObjectMapper mapper, Clock clock) {
        return switch (configuration.providerId()) {
            case "aliyun" ->
                new AliyunSearchProvider(
                        client,
                        mapper,
                        configuration.endpoint(),
                        configuration.timeout(),
                        configuration.maxResponseBytes(),
                        clock);
            case "brave" ->
                new BraveWebSearchProvider(
                        client,
                        mapper,
                        configuration.endpoint(),
                        configuration.timeout(),
                        configuration.maxResponseBytes(),
                        clock);
            case "tavily" ->
                new TavilyWebSearchProvider(
                        client,
                        mapper,
                        configuration.endpoint(),
                        configuration.timeout(),
                        configuration.maxResponseBytes(),
                        clock);
            default -> throw new IllegalArgumentException("unsupported Web Search provider");
        };
    }

    private static WebFetchProvider fetchProvider(
            CliConfiguration.WebProvider configuration, HttpClient client, ObjectMapper mapper, Clock clock) {
        return switch (configuration.providerId()) {
            case "aliyun" ->
                new AliyunFetchProvider(
                        client,
                        mapper,
                        configuration.endpoint(),
                        configuration.timeout(),
                        configuration.maxResponseBytes(),
                        clock);
            case "browserless" ->
                new BrowserlessFetchProvider(
                        client,
                        mapper,
                        configuration.endpoint(),
                        configuration.timeout(),
                        configuration.maxResponseBytes(),
                        clock);
            case "tavily" ->
                new TavilyFetchProvider(
                        client,
                        mapper,
                        configuration.endpoint(),
                        configuration.timeout(),
                        configuration.maxResponseBytes(),
                        clock);
            default -> throw new IllegalArgumentException("unsupported Web Fetch provider");
        };
    }
}
