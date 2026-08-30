package io.haifa.example.sdk.advanced;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.product.ProductArtifactPolicy;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductCapabilityId;
import io.haifa.agent.sdk.product.ProductCapabilityRequirement;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductExecutionPolicy;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductMemoryPolicy;
import io.haifa.agent.sdk.product.ProductPolicies;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import io.haifa.agent.sdk.product.ProductVersion;
import io.haifa.agent.store.sqlite.SqliteSdkProductContributions;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.example.sdk.support.DeterministicExampleSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Single-process durable reference assembly using the existing SQLite contributions.
 *
 * <p>This class belongs to an unpublished application example module. It is host integration
 * guidance, not SDK API or a production compatibility commitment. Applications should copy and
 * adapt the assembly decisions instead of depending on this class.
 */
public final class SqliteDurableReferenceAssemblyExample {
    private static final String VERSION = "1.0.0";
    private static final ProductContributionCoordinate MODEL = coordinate("example.model");
    private static final ProductContributionCoordinate PERSISTENCE = coordinate("example.sqlite.persistence");
    private static final ProductContributionCoordinate CONVERSATION = coordinate("example.sqlite.conversation");
    private static final ProductContributionCoordinate MEMORY = coordinate("example.sqlite.memory");
    private static final ProductContributionCoordinate POLICY = coordinate("example.sqlite.policy");
    private static final ProductContributionCoordinate ARTIFACT =
            new ProductContributionCoordinate("haifa-sqlite-artifact", VERSION);

    private SqliteDurableReferenceAssemblyExample() {}

    /** Opens one example-owned SQLite Agent. Closing the Agent closes the SQLite foundation. */
    static HaifaAgent open(
            Path dataDirectory,
            SecretKey continuationKey,
            AgentChatModel model,
            ResolvedModelSnapshot snapshot,
            SdkCallerProvider callers) {
        Path database = dataDirectory.toAbsolutePath().normalize().resolve("haifa-agent.sqlite");
        var sqlite = SqliteSdkProductContributions.initialize(
                SqliteStoreConfiguration.defaults(database),
                Clock.systemUTC(),
                new AesGcmModelContinuationProtector(continuationKey, new SecureRandom()),
                metadata(PERSISTENCE, ProductCapabilities.PERSISTENCE, "sqlite-runtime-v7"),
                metadata(CONVERSATION, ProductCapabilities.CONVERSATION, "sqlite-conversation-v1"),
                metadata(MEMORY, ProductCapabilities.MEMORY, "sqlite-memory-v1"),
                metadata(POLICY, ProductCapabilities.POLICY, "sqlite-policy-v1"));
        ModelContribution models = new ModelContribution(
                new SdkContributionMetadata(
                        MODEL,
                        ProductCapabilities.MODEL,
                        snapshot.configurationDigest(),
                        ProductProviderSuitability.PRODUCTION,
                        "Application-owned model catalog"),
                Map.of(ModelAdapterCoordinate.from(snapshot), model),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
        try {
            return HaifaAgents.builder(profile(snapshot))
                    .callerProvider(callers)
                    .contribute(models)
                    .contribute(sqlite.persistence())
                    .contribute(sqlite.conversation())
                    .contribute(sqlite.memory())
                    .contribute(sqlite.policy())
                    .contribute(sqlite.artifact())
                    .build();
        } catch (RuntimeException | Error exception) {
            sqlite.persistence().close();
            throw exception;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: <existing-data-directory>");
        Path dataDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(dataDirectory);
        byte[] key = Base64.getDecoder().decode(requiredEnvironment("HAIFA_CONTINUATION_KEY_BASE64"));
        try (var agent = open(
                dataDirectory,
                new SecretKeySpec(key, "AES"),
                DeterministicExampleSupport.model("sqlite-answer"),
                DeterministicExampleSupport.snapshot(),
                SdkCallerProvider.defaultPublicUser())) {
            System.out.println(agent.assembly().assemblyDigest());
        }
    }

    private static ProductProfile profile(ResolvedModelSnapshot snapshot) {
        Map<ProductCapabilityId, ProductCapabilityRequirement> requirements = new LinkedHashMap<>();
        require(requirements, ProductCapabilities.MODEL, MODEL);
        require(requirements, ProductCapabilities.PERSISTENCE, PERSISTENCE);
        require(requirements, ProductCapabilities.CONVERSATION, CONVERSATION);
        require(requirements, ProductCapabilities.MEMORY, MEMORY);
        require(requirements, ProductCapabilities.POLICY, POLICY);
        require(requirements, ProductCapabilities.ARTIFACT, ARTIFACT);
        ProductPolicies policies = new ProductPolicies(
                ProductMemoryPolicy.safeDefault(),
                new ProductArtifactPolicy(
                        1_048_576,
                        16,
                        16L * 1_048_576,
                        Set.of("application/json", "text/markdown"),
                        false,
                        64L * 1_048_576,
                        128L * 1_048_576,
                        false),
                ProductExecutionPolicy.disabled());
        return ProductProfile.create(
                new ProductId("sdk-sqlite-example"),
                new ProductVersion(VERSION),
                new AgentDefinitionId("sdk-sqlite-example-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                snapshot.modelId().value(),
                VERSION,
                "Answer carefully using only explicitly contributed capabilities.",
                new AgentRunBudget(65_536, 8_192, 65_536, 16, 16, 0, "USD", 100),
                new AgentRunLimits(16, 0, 1, 120_000, 60_000, 16, 16, 0),
                policies,
                requirements,
                Set.of(),
                Set.of(),
                Set.of());
    }

    private static void require(
            Map<ProductCapabilityId, ProductCapabilityRequirement> requirements,
            ProductCapabilityId capability,
            ProductContributionCoordinate coordinate) {
        requirements.put(
                capability,
                ProductCapabilityRequirement.required(
                        capability, Set.of(coordinate), ProductProviderSuitability.PRODUCTION));
    }

    private static SdkContributionMetadata metadata(
            ProductContributionCoordinate coordinate, ProductCapabilityId capability, String digestSeed) {
        return new SdkContributionMetadata(
                coordinate,
                capability,
                SdkConfigurationDigest.sha256(digestSeed),
                ProductProviderSuitability.PRODUCTION,
                "SQLite " + capability.value());
    }

    private static ProductContributionCoordinate coordinate(String providerId) {
        return new ProductContributionCoordinate(providerId, VERSION);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is not configured");
        return value;
    }
}
