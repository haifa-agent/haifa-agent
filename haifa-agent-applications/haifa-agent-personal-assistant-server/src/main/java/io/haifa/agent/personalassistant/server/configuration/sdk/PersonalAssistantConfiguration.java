package io.haifa.agent.personalassistant.server.configuration.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
import io.haifa.agent.auth.localmodel.ExternalLoginCoordinator;
import io.haifa.agent.auth.localmodel.ExternalLoginMethod;
import io.haifa.agent.auth.localmodel.ExternalLoginRegistry;
import io.haifa.agent.auth.localmodel.FileLocalModelAuthStore;
import io.haifa.agent.auth.localmodel.LocalModelAuthenticationService;
import io.haifa.agent.auth.localmodel.LocalModelCredentialResolver;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityExternalLoginMethod;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityLocalCompatibilityRegistrationFactory;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityProjectRegistry;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityTokenClient;
import io.haifa.agent.auth.localmodel.codex.CodexDeviceLoginOperation;
import io.haifa.agent.auth.localmodel.codex.CodexExternalLoginMethod;
import io.haifa.agent.auth.localmodel.codex.CodexLocalCompatibilityRegistrationFactory;
import io.haifa.agent.auth.localmodel.codex.CodexTokenClient;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.openai.responses.CodexAccountIdentity;
import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.application.PersonalAssistantAssembler;
import io.haifa.agent.personalassistant.application.execution.PersonalExecutionPlatform;
import io.haifa.agent.personalassistant.application.mission.DeterministicMissionPlanner;
import io.haifa.agent.personalassistant.application.mission.MissionApplicationService;
import io.haifa.agent.personalassistant.application.mission.MissionExecutionCoordinator;
import io.haifa.agent.personalassistant.application.mission.MissionPlanValidator;
import io.haifa.agent.personalassistant.application.mission.MissionPlanner;
import io.haifa.agent.personalassistant.application.web.PersonalWebPlatform;
import io.haifa.agent.personalassistant.server.audio.PersonalAudioStore;
import io.haifa.agent.personalassistant.server.configuration.execution.PersonalExecutionRuntime;
import io.haifa.agent.personalassistant.server.configuration.mcp.PersonalMcpRuntime;
import io.haifa.agent.personalassistant.server.configuration.model.PersonalModelFactory;
import io.haifa.agent.personalassistant.server.configuration.model.PersonalModelProxySelector;
import io.haifa.agent.personalassistant.server.configuration.model.PersonalModelProxySettings;
import io.haifa.agent.personalassistant.server.configuration.model.SqlitePersonalModelPreferenceStore;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.personalassistant.server.image.PersonalImageStore;
import io.haifa.agent.personalassistant.server.mission.MissionArtifactPublisher;
import io.haifa.agent.personalassistant.server.mission.MissionCapacityMonitor;
import io.haifa.agent.personalassistant.server.mission.MissionDispatcher;
import io.haifa.agent.personalassistant.server.mission.MissionOperationsService;
import io.haifa.agent.personalassistant.server.mission.RuntimeMissionPlanner;
import io.haifa.agent.personalassistant.server.mission.SqliteMissionStore;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import io.haifa.agent.store.sqlite.SqliteSdkProductContributions;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import java.awt.Desktop;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit Spring composition root. Spring discovers this root, not individual SDK contributions. */
@Configuration(proxyBeanMethods = false)
public class PersonalAssistantConfiguration {
    @Bean
    Clock personalClock() {
        return Clock.systemUTC();
    }

    @Bean
    AntigravityProjectRegistry antigravityProjectRegistry() {
        return new AntigravityProjectRegistry();
    }

    @Bean
    PersonalModelProxySettings personalModelProxySettings(PersonalAssistantProperties properties, ObjectMapper mapper) {
        return new PersonalModelProxySettings(properties.modelProviders(), prepare(properties.dataDirectory()), mapper);
    }

    @Bean
    ProxySelector personalModelProxySelector(
            PersonalAssistantProperties properties, PersonalModelProxySettings settings) {
        return PersonalModelProxySelector.from(properties.modelProviders(), settings, ProxySelector.getDefault());
    }

    @Bean(destroyMethod = "close")
    PersonalMcpRuntime personalMcpRuntime(PersonalAssistantProperties properties, ObjectMapper mapper) {
        return new PersonalMcpRuntime(properties.mcp(), mapper);
    }

    @Bean(destroyMethod = "close")
    LocalModelAuthenticationService personalModelAuthenticationService(
            PersonalAssistantProperties properties,
            ObjectMapper mapper,
            Clock personalClock,
            AntigravityProjectRegistry antigravityProjects,
            PersonalModelProxySettings proxySettings) {
        Map<String, String> environment = System.getenv();
        var store = FileLocalModelAuthStore.defaultStore(mapper);
        var codexRegistration = CodexLocalCompatibilityRegistrationFactory.create(environment);
        var antigravityRegistration = AntigravityLocalCompatibilityRegistrationFactory.create(environment);
        HttpClient codexHttp = authenticationHttpClient(proxySettings, "openai-codex");
        HttpClient antigravityHttp = authenticationHttpClient(proxySettings, "google-antigravity");
        List<ExternalLoginMethod> methods = new java.util.ArrayList<>();
        codexRegistration.ifPresent(value -> methods.add(new CodexExternalLoginMethod(
                value,
                new CodexTokenClient(codexHttp, mapper, personalClock, Duration.ofSeconds(30), value),
                codexHttp,
                mapper,
                SecureRandom::new,
                Duration.ofMinutes(5),
                CodexDeviceLoginOperation.Sleeper.system())));
        antigravityRegistration.ifPresent(value -> methods.add(new AntigravityExternalLoginMethod(
                value,
                new AntigravityTokenClient(antigravityHttp, mapper, personalClock, Duration.ofSeconds(30), value),
                antigravityHttp,
                mapper,
                SecureRandom::new,
                Duration.ofMinutes(5),
                projection -> antigravityProjects.record(
                        new CredentialRef("model-auth://google-antigravity/default"), projection))));
        var registry = new ExternalLoginRegistry(methods);
        var resolver = new LocalModelCredentialResolver(
                environment::get, store, registry, personalClock, Duration.ofMinutes(5));
        var identifiers = new UuidV7IdentifierGenerator();
        var coordinator = methods.isEmpty()
                ? Optional.<ExternalLoginCoordinator>empty()
                : Optional.of(new ExternalLoginCoordinator(
                        registry,
                        store,
                        () -> new ExternalLoginAttemptId(identifiers.nextValue()),
                        personalClock,
                        Executors.newFixedThreadPool(2, runnable -> {
                            Thread thread = new Thread(runnable, "haifa-personal-model-auth");
                            thread.setDaemon(true);
                            return thread;
                        }),
                        PersonalAssistantConfiguration::openBrowser,
                        8));
        return new LocalModelAuthenticationService(store, coordinator, resolver, environment::get);
    }

    static HttpClient authenticationHttpClient(PersonalModelProxySettings settings, String providerId) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(Objects.requireNonNull(settings, "settings must not be null")
                        .providerSelector(providerId, ProxySelector.getDefault()))
                .build();
    }

    @Bean(destroyMethod = "close")
    PersonalAssistantApplication personalAssistantApplication(
            PersonalAssistantProperties properties,
            ObjectMapper mapper,
            Clock personalClock,
            PersonalMcpRuntime mcpRuntime,
            PersonalImageStore imageStore,
            PersonalAudioStore audioStore,
            LocalModelAuthenticationService modelAuthentication,
            AntigravityProjectRegistry antigravityProjects,
            ProxySelector personalModelProxySelector) {
        Path dataDirectory = prepare(properties.dataDirectory());
        byte[] key = decodeKey(properties.continuationKeyBase64());
        ModelContinuationProtector protector =
                new AesGcmModelContinuationProtector(new SecretKeySpec(key, "AES"), new SecureRandom());
        var sqlite = SqliteSdkProductContributions.initialize(
                new SqliteStoreConfiguration(
                        dataDirectory.resolve("personal-assistant.sqlite").toAbsolutePath(), 1_250, 4 * 1024 * 1024),
                personalClock,
                protector,
                metadata("haifa-personal-sqlite", ProductCapabilities.PERSISTENCE, "runtime-v1"),
                metadata("haifa-personal-conversation", ProductCapabilities.CONVERSATION, "conversation-v1"),
                metadata("haifa-personal-memory", ProductCapabilities.MEMORY, "memory-v1"),
                metadata("haifa-personal-policy", ProductCapabilities.POLICY, "policy-v1"));
        TenantRef tenant = new TenantRef(properties.caller().tenant());
        PrincipalRef principal = new PrincipalRef(properties.caller().principal(), "user");
        SdkCaller caller = new SdkCaller(tenant, principal, Set.of("memory:read", "memory:propose", "memory:review"));
        Optional<Path> localSkillRoot = properties.localSkillRoot().isBlank()
                ? Optional.empty()
                : Optional.of(
                        Path.of(properties.localSkillRoot()).toAbsolutePath().normalize());
        Optional<Path> trustedScriptManifest =
                properties.trustedScriptManifest().isBlank()
                        ? Optional.empty()
                        : Optional.of(Path.of(properties.trustedScriptManifest())
                                .toAbsolutePath()
                                .normalize());
        PersonalExecutionPlatform execution = null;
        try {
            execution = PersonalExecutionRuntime.create(
                    dataDirectory, principal, properties.execution(), sqlite.policy(), personalClock);
            var web = "deterministic-stub".equals(properties.mission().plannerMode())
                            && !properties.web().enabled()
                    ? PersonalWebPlatform.deterministicStub()
                    : PersonalWebPlatform.create(
                            tenant,
                            principal,
                            providerConfiguration(properties.web().search()),
                            providerConfiguration(properties.web().fetch()),
                            mapper,
                            personalClock);
            var models = PersonalModelFactory.createPlatform(
                    properties.modelProviders(),
                    properties.defaultModelId(),
                    properties.allowInsecureLoopbackModel(),
                    mapper,
                    execution.shell(),
                    modelAuthentication.credentialResolver(),
                    antigravityProjects::resolve,
                    ref -> modelAuthentication
                            .findExternalAccountId(ref, CodexExternalLoginMethod.METHOD_ID)
                            .map(CodexAccountIdentity::new),
                    personalModelProxySelector);
            var modelPreferences = new SqlitePersonalModelPreferenceStore(
                    dataDirectory.resolve("personal-assistant.sqlite"),
                    properties.caller().tenant(),
                    properties.caller().principal(),
                    mapper);
            return PersonalAssistantAssembler.assemble(new PersonalAssistantAssembler.Dependencies(
                    tenant,
                    principal,
                    () -> caller,
                    models.contribution(),
                    models.catalog(),
                    modelPreferences,
                    sqlite.persistence(),
                    sqlite.conversation(),
                    sqlite.memory(),
                    sqlite.policy(),
                    sqlite.artifact(),
                    execution,
                    web,
                    mcpRuntime.configuration(),
                    localSkillRoot,
                    trustedScriptManifest,
                    List.of(
                            dataDirectory,
                            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()),
                    personalClock,
                    imageStore,
                    audioStore));
        } catch (RuntimeException | Error exception) {
            if (execution != null) {
                try {
                    execution.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            try {
                sqlite.persistence().close();
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    @Bean
    PersonalImageStore personalImageStore(PersonalAssistantProperties properties) {
        return new PersonalImageStore(prepare(properties.dataDirectory()));
    }

    @Bean
    PersonalAudioStore personalAudioStore(PersonalAssistantProperties properties) {
        return new PersonalAudioStore(prepare(properties.dataDirectory()));
    }

    @Bean
    SqliteMissionStore personalMissionStore(PersonalAssistantProperties properties, ObjectMapper mapper) {
        var limits = properties.mission();
        return new SqliteMissionStore(
                properties.dataDirectory().resolve("personal-assistant.sqlite"),
                mapper,
                limits.maxAutoAttemptsPerTask(),
                Math.min(3, limits.maxAutoAttemptsPerTask() + 1),
                limits.maxModelTokens(),
                limits.maxToolCalls());
    }

    @Bean
    MissionCapacityMonitor missionCapacityMonitor(PersonalAssistantProperties properties) {
        return new MissionCapacityMonitor(properties.dataDirectory(), properties.mission());
    }

    @Bean
    MissionPlanner personalMissionPlanner(
            PersonalAssistantProperties properties,
            PersonalAssistantApplication application,
            MissionPlanValidator missionPlanValidator,
            ObjectMapper mapper) {
        return switch (properties.mission().plannerMode()) {
            case "deterministic-stub" -> new DeterministicMissionPlanner();
            case "runtime" -> new RuntimeMissionPlanner(application.missionRuntime(), missionPlanValidator, mapper);
            default -> throw new IllegalStateException("unsupported Mission Planner mode");
        };
    }

    @Bean
    MissionPlanValidator missionPlanValidator() {
        return new MissionPlanValidator(
                Set.of("GENERAL", "RESEARCH"),
                Set.of("deep-research"),
                Set.of("pa.task-result@v1", "pa.research-task-result@v1"));
    }

    @Bean
    MissionApplicationService missionApplicationService(
            SqliteMissionStore store,
            MissionPlanner planner,
            MissionPlanValidator missionPlanValidator,
            Clock clock,
            PersonalAssistantApplication application,
            MissionOperationsService operations) {
        var ids = new UuidV7IdentifierGenerator();
        return new MissionApplicationService(
                store,
                store,
                planner,
                missionPlanValidator,
                ids::nextValue,
                clock,
                store,
                application
                        .skillBindingReference("deep-research")
                        .map(binding -> java.util.Map.of("deep-research", binding))
                        .orElseGet(java.util.Map::of),
                operations::requireAdmission);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    MissionDispatcher missionDispatcher(
            SqliteMissionStore store,
            PersonalAssistantApplication application,
            PersonalAssistantProperties properties,
            Clock clock,
            ObjectMapper mapper,
            MissionCapacityMonitor capacity) {
        String dispatcherId = "pa-mission-" + ProcessHandle.current().pid();
        var coordinator = new MissionExecutionCoordinator(
                store,
                application.missionRuntime(),
                clock,
                dispatcherId,
                new MissionArtifactPublisher(
                        application.artifacts(),
                        mapper,
                        properties.research().maxSources(),
                        properties.research().maxTotalContentBytes(),
                        properties.mission().maxArtifacts(),
                        properties.mission().maxTotalArtifactBytes()));
        return new MissionDispatcher(
                store,
                coordinator,
                clock,
                properties.dataDirectory(),
                capacity,
                properties.mission().dispatcherPollMillis(),
                properties.mission().dispatcherShutdownTimeoutMillis());
    }

    private static PersonalWebPlatform.ProviderConfiguration providerConfiguration(
            PersonalAssistantProperties.WebProvider provider) {
        return new PersonalWebPlatform.ProviderConfiguration(
                provider.enabled(),
                provider.providerId(),
                provider.endpoint(),
                resolveCredential(provider),
                Duration.ofMillis(provider.timeoutMillis()),
                provider.maximumResponseBytes());
    }

    private static boolean openBrowser(URI uri) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false;
        try {
            Desktop.getDesktop().browse(uri);
            return true;
        } catch (IOException | SecurityException exception) {
            return false;
        }
    }

    private static String resolveCredential(PersonalAssistantProperties.WebProvider provider) {
        if (!provider.enabled()) return "";
        String variable = provider.credentialReference().substring("env://".length());
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Personal Web Tool credential environment variable is unavailable: " + variable);
        }
        return value;
    }

    private static SdkContributionMetadata metadata(
            String id, io.haifa.agent.sdk.product.ProductCapabilityId capability, String configuration) {
        return new SdkContributionMetadata(
                new ProductContributionCoordinate(id, "1.0.0"),
                capability,
                SdkConfigurationDigest.sha256(id, configuration),
                ProductProviderSuitability.PRODUCTION,
                "Personal Assistant " + capability.value());
    }

    private static Path prepare(Path value) {
        Path path = value.toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
            return path.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Personal Assistant data directory is unavailable", exception);
        }
    }

    private static byte[] decodeKey(String encoded) {
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != 32) throw new IllegalArgumentException("continuation key must decode to 32 bytes");
            return key;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("continuation key must be base64-encoded AES-256 material", exception);
        }
    }
}
