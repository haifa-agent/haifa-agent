package io.haifa.agent.personalassistant.application.web;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.CredentialPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.core.ToolDefinitionCanonicalizer;
import io.haifa.agent.web.DefaultWebUrlPolicy;
import io.haifa.agent.web.WebToolCatalog;
import io.haifa.agent.web.WebToolCatalogContribution;
import io.haifa.agent.web.provider.AliyunFetchProvider;
import io.haifa.agent.web.provider.AliyunSearchProvider;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.KeyGenerator;

/** Product-level Aliyun IQS Web Tool selection and in-memory credential binding. */
public record PersonalWebPlatform(
        List<WebToolCatalogContribution> contributions, CredentialPlatformContribution credential) {
    public static final ProductContributionCoordinate CREDENTIAL_COORDINATE =
            new ProductContributionCoordinate("haifa-personal-web-credentials", "1.0.0");

    public PersonalWebPlatform {
        contributions = List.copyOf(contributions);
        java.util.Objects.requireNonNull(credential);
    }

    public static PersonalWebPlatform create(
            TenantRef tenant,
            PrincipalRef principal,
            boolean enabled,
            String apiKey,
            Duration timeout,
            int searchMaxResponseBytes,
            int fetchMaxResponseBytes,
            ObjectMapper mapper,
            Clock clock) {
        java.util.Objects.requireNonNull(tenant);
        java.util.Objects.requireNonNull(principal);
        java.util.Objects.requireNonNull(timeout);
        java.util.Objects.requireNonNull(mapper);
        java.util.Objects.requireNonNull(clock);
        if (!enabled) {
            return platform(List.of(), emptyBroker());
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Aliyun IQS credential is required when Personal Web Tools are enabled");
        }

        var client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var catalog = new WebToolCatalog();
        List<WebToolCatalogContribution> contributions = List.of(
                catalog.search(new AliyunSearchProvider(
                        client, mapper, AliyunSearchProvider.DEFAULT_ENDPOINT, timeout, searchMaxResponseBytes, clock)),
                catalog.fetch(
                        new AliyunFetchProvider(
                                client,
                                mapper,
                                AliyunFetchProvider.DEFAULT_ENDPOINT,
                                timeout,
                                fetchMaxResponseBytes,
                                clock),
                        new DefaultWebUrlPolicy()));
        return platform(contributions, credentialBroker(tenant, principal, contributions, apiKey));
    }

    public Set<String> aliases() {
        return contributions.stream()
                .map(item -> item.alias().value())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static PersonalWebPlatform platform(
            List<WebToolCatalogContribution> contributions, DefaultCredentialBroker broker) {
        String digest = SdkConfigurationDigest.sha256(contributions.stream()
                .map(WebToolCatalogContribution::providerBindingReference)
                .sorted()
                .toArray(String[]::new));
        var metadata = new SdkContributionMetadata(
                CREDENTIAL_COORDINATE,
                ProductCapabilities.CREDENTIAL,
                digest,
                ProductProviderSuitability.PRODUCTION,
                "Personal Assistant Web Tool credentials");
        return new PersonalWebPlatform(contributions, new CredentialPlatformContribution(metadata, broker));
    }

    private static DefaultCredentialBroker credentialBroker(
            TenantRef tenant, PrincipalRef principal, List<WebToolCatalogContribution> contributions, String apiKey) {
        var store = encryptedStore();
        List<CredentialDefinition> definitions = new ArrayList<>();
        List<CredentialBinding> bindings = new ArrayList<>();
        for (WebToolCatalogContribution contribution : contributions) {
            var requirement = contribution.definition().credentialRequirements().stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Web provider credential requirement is missing"));
            String operation = contribution.definition().name().value();
            String suffix = operation.substring("web.".length()) + "-aliyun";
            var reference = new CredentialReference("personal-web-" + suffix);
            byte[] secretBytes = apiKey.getBytes(StandardCharsets.UTF_8);
            try {
                store.store(reference, tenant, requirement.definitionId(), secretBytes);
            } finally {
                Arrays.fill(secretBytes, (byte) 0);
            }
            definitions.add(new CredentialDefinition(
                    requirement.definitionId(),
                    "aliyun",
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
                    "personal-web-" + suffix,
                    tenant,
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
        return new DefaultCredentialBroker(definitions, bindings, new DefaultCredentialResolver(), store);
    }

    private static DefaultCredentialBroker emptyBroker() {
        return new DefaultCredentialBroker(List.of(), List.of(), new DefaultCredentialResolver(), encryptedStore());
    }

    private static AesGcmCredentialStore encryptedStore() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            var key = generator.generateKey();
            return new AesGcmCredentialStore(() -> key);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "unable to initialize the in-memory Personal Web credential store", exception);
        }
    }
}
