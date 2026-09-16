package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.InMemoryArtifactPayloadStore;
import io.haifa.agent.artifact.InMemoryArtifactStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.contribution.ArtifactPlatformContribution;
import io.haifa.agent.sdk.conversation.ConversationStore;
import io.haifa.agent.sdk.conversation.InMemoryConversationStore;
import io.haifa.agent.sdk.spi.SdkConversationContribution;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class ProductIsolationAndLifecycleTest {

    @Test
    void missingRequiredComponentsFailClosedWithMeaningfulCodes() {
        var profile = SdkTestFixtures.profile("missing");

        assertThatThrownBy(() -> HaifaAgents.builder(profile).build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("MODEL_REQUIRED");

        assertThatThrownBy(() -> HaifaAgents.builder(profile)
                        .model(SdkTestFixtures.modelContribution())
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("PERSISTENCE_REQUIRED");

        assertThatThrownBy(() -> HaifaAgents.builder(profile)
                        .model(SdkTestFixtures.modelContribution())
                        .persistence(SdkTestFixtures.persistenceContribution())
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("CONVERSATION_REQUIRED");
    }

    @Test
    void optionalComponentsMissingMeansCapabilityAbsent() {
        try (HaifaAgent agent = SdkTestFixtures.builder("optional").build()) {
            assertThat(agent.memories()).isEmpty();
            assertThat(agent.artifacts()).isEmpty();
            assertThat(agent.diagnostics()).isEmpty();
        }
    }

    @Test
    void artifactComponentRequiresEnabledArtifactPolicy() {
        var artifact = new ArtifactPlatformContribution(
                new ArtifactService(
                        new InMemoryArtifactStore(),
                        new InMemoryArtifactPayloadStore(),
                        () -> "artifact-test-id",
                        () -> Instant.parse("2026-07-28T00:00:00Z")),
                io.haifa.agent.sdk.product.ProductArtifactPolicy.disabled());
        var profile = SdkTestFixtures.profile("artifact-disabled");

        assertThatThrownBy(() -> HaifaAgents.builder(profile)
                        .model(SdkTestFixtures.modelContribution())
                        .persistence(SdkTestFixtures.persistenceContribution())
                        .conversation(SdkTestFixtures.conversationContribution())
                        .artifacts(artifact)
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("ARTIFACT_POLICY_DISABLED");
    }

    @Test
    void profileAllowingUnavailableToolAliasFailsBuild() {
        var profile = SdkTestFixtures.profile("tool-alias", Set.of("weather_get"), Set.of());

        assertThatThrownBy(() -> HaifaAgents.builder(profile)
                        .model(SdkTestFixtures.modelContribution())
                        .persistence(SdkTestFixtures.persistenceContribution())
                        .conversation(SdkTestFixtures.conversationContribution())
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("TOOL_ALIAS_UNAVAILABLE");
    }

    @Test
    void closesOwnedComponentsExactlyOnce() {
        AtomicInteger closes = new AtomicInteger();
        SdkPersistenceContribution persistence = new SdkPersistenceContribution() {
            private final RuntimePersistencePorts ports = RuntimePersistencePorts.inMemory();

            @Override
            public RuntimePersistencePorts runtimePersistence() {
                return ports;
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        HaifaAgent agent = HaifaAgents.builder(SdkTestFixtures.profile("lifecycle"))
                .model(SdkTestFixtures.modelContribution())
                .persistence(persistence)
                .conversation(SdkTestFixtures.conversationContribution())
                .build();

        agent.close();
        agent.close();
        assertThat(closes).hasValue(1);
    }

    @Test
    void closesASharedComponentRegisteredAsBothPersistenceAndConversationOnce() {
        AtomicInteger closes = new AtomicInteger();
        CombinedStorage storage = new CombinedStorage(closes);
        HaifaAgent agent = HaifaAgents.builder(SdkTestFixtures.profile("shared-component"))
                .model(SdkTestFixtures.modelContribution())
                .persistence(storage)
                .conversation(storage)
                .build();

        agent.close();
        assertThat(closes).hasValue(1);
    }

    @Test
    void duplicateJavaToolAliasesFailBeforeRuntimeAssembly() {
        assertThatThrownBy(() -> SdkTestFixtures.builder("java-tool-conflict")
                        .tools(java.util.List.of(new WeatherTool(), new DuplicateWeatherTool()))
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("JAVA_TOOL_ALIAS_CONFLICT");
    }

    public record WeatherRequest(String city) {}

    public record WeatherResponse(String forecast) {}

    private static final class WeatherTool implements JavaTool<WeatherRequest, WeatherResponse> {
        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return JavaToolSpec.builder("weather_get", WeatherRequest.class, WeatherResponse.class)
                    .pure()
                    .build();
        }

        @Override
        public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
            return new WeatherResponse("Sunny in " + input.city());
        }
    }

    private static final class DuplicateWeatherTool implements JavaTool<WeatherRequest, WeatherResponse> {
        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return JavaToolSpec.builder("weather_get", WeatherRequest.class, WeatherResponse.class)
                    .pure()
                    .build();
        }

        @Override
        public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
            return new WeatherResponse(input.city());
        }
    }

    private static final class CombinedStorage implements SdkPersistenceContribution, SdkConversationContribution {
        private final RuntimePersistencePorts ports = RuntimePersistencePorts.inMemory();
        private final ConversationStore conversations = new InMemoryConversationStore();
        private final AtomicInteger closes;

        private CombinedStorage(AtomicInteger closes) {
            this.closes = closes;
        }

        @Override
        public RuntimePersistencePorts runtimePersistence() {
            return ports;
        }

        @Override
        public ConversationStore conversationStore() {
            return conversations;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
