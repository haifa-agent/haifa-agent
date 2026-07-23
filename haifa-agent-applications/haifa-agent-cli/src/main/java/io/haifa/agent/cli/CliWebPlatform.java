package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.tool.web.DefaultWebUrlPolicy;
import io.haifa.agent.application.project.tool.web.WebFetchProvider;
import io.haifa.agent.application.project.tool.web.WebFetchProviderRegistry;
import io.haifa.agent.application.project.tool.web.WebProviderId;
import io.haifa.agent.application.project.tool.web.WebSearchProvider;
import io.haifa.agent.application.project.tool.web.WebSearchProviderRegistry;
import io.haifa.agent.application.project.tool.web.WebToolCatalog;
import io.haifa.agent.application.project.tool.web.WebToolCatalogContribution;
import io.haifa.agent.application.project.tool.web.provider.AliyunFetchProvider;
import io.haifa.agent.application.project.tool.web.provider.AliyunSearchProvider;
import io.haifa.agent.application.project.tool.web.provider.BraveWebSearchProvider;
import io.haifa.agent.application.project.tool.web.provider.TavilyWebSearchProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.credential.api.CredentialBinding;
import io.haifa.agent.credential.api.CredentialBindingScope;
import io.haifa.agent.credential.api.CredentialDefinition;
import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialReference;
import io.haifa.agent.credential.api.CredentialScopeKind;
import io.haifa.agent.credential.api.CredentialStatus;
import io.haifa.agent.credential.api.CredentialType;
import io.haifa.agent.credential.core.AesGcmCredentialStore;
import io.haifa.agent.credential.core.DefaultCredentialBroker;
import io.haifa.agent.credential.core.DefaultCredentialResolver;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.core.ToolDefinitionCanonicalizer;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.crypto.KeyGenerator;

/** Product-level Web provider selection and Credential Broker assembly for the local CLI profile. */
final class CliWebPlatform {
    private static final TenantRef LOCAL_TENANT = new TenantRef("local");
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
            return new CliWebPlatform(
                    List.of(),
                    new DefaultCredentialBroker(
                            List.of(), List.of(), new DefaultCredentialResolver(), encryptedStore()));
        }

        var store = encryptedStore();
        List<CredentialDefinition> definitions = new ArrayList<>();
        List<CredentialBinding> bindings = new ArrayList<>();
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
            String suffix = operation.substring("web.".length()) + "-" + providerConfiguration.providerId();
            var reference = new CredentialReference("cli-web-" + suffix);
            byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
            try {
                store.store(reference, LOCAL_TENANT, requirement.definitionId(), secretBytes);
            } finally {
                Arrays.fill(secretBytes, (byte) 0);
            }
            definitions.add(new CredentialDefinition(
                    requirement.definitionId(),
                    providerConfiguration.providerId(),
                    CredentialType.API_KEY,
                    requirement.scopes(),
                    Set.of(CredentialExposureMode.HTTP_HEADER),
                    Map.of("source", "environment-reference")));
            ToolCoordinate coordinate = new ToolCoordinate(
                    contribution.definition().name(),
                    contribution.definition().version(),
                    contribution.definition().providerId(),
                    new ToolDefinitionCanonicalizer().hash(contribution.definition()));
            bindings.add(new CredentialBinding(
                    "cli-web-" + suffix,
                    LOCAL_TENANT,
                    Optional.of(principal),
                    requirement.definitionId(),
                    reference,
                    new CredentialBindingScope(CredentialScopeKind.SYSTEM, "system"),
                    Set.of(coordinate.externalForm()),
                    Set.of(requirement.purpose()),
                    requirement.scopes(),
                    Set.of(CredentialExposureMode.HTTP_HEADER),
                    CredentialStatus.ACTIVE,
                    Optional.empty()));
        }
        var broker = new DefaultCredentialBroker(definitions, bindings, new DefaultCredentialResolver(), store);
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
        if (!configuration.providerId().equals("aliyun")) {
            throw new IllegalArgumentException("only Aliyun Web Fetch is supported");
        }
        return new AliyunFetchProvider(
                client,
                mapper,
                configuration.endpoint(),
                configuration.timeout(),
                configuration.maxResponseBytes(),
                clock);
    }

    private static AesGcmCredentialStore encryptedStore() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            var key = generator.generateKey();
            return new AesGcmCredentialStore(() -> key);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("unable to initialize the in-memory Web credential store", exception);
        }
    }
}
