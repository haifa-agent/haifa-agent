package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.conversation.ChangeConversationStatusCommand;
import io.haifa.agent.sdk.conversation.ConversationException;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.ConversationStatus;
import io.haifa.agent.sdk.conversation.RenameConversationCommand;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class HaifaAgentFacadeTest {
    @Test
    void exposesBoundedTypedModelRetryConfiguration() {
        HaifaAgentBuilder builder = HaifaAgents.builder();

        assertThat(builder.modelRetry(3, Duration.ofMillis(100), Duration.ofSeconds(2), 2.0d, 0.2d))
                .isSameAs(builder);
        assertThatThrownBy(() -> builder.modelRetry(3, Duration.ofMillis(100), Duration.ofSeconds(2), 2.0d, 1.1d))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jitterRatio");
    }

    @Test
    void registersOneTypedJavaToolWithoutManualPlatformAssembly() {
        try (HaifaAgent agent = HaifaAgents.builder()
                .product(SdkTestFixtures.profile("java-tool", Map.of()))
                .contributeAll(SdkTestFixtures.baseContributions())
                .tool(new WeatherTool())
                .build()) {
            assertThat(agent.assembly().profile().allowedTools()).containsExactly("weather_get");
            assertThat(agent.assembly()
                            .profile()
                            .requirement(ProductCapabilities.TOOL)
                            .allowedContributions())
                    .extracting("providerId")
                    .containsExactly("sdk.java-tools");
        }
    }

    @Test
    void registersAListOfToolsWithoutMutatingAnotherBuilder() {
        ProductProfile profile = SdkTestFixtures.profile("java-tool-list", Map.of());

        try (HaifaAgent withTools = HaifaAgents.builder(profile)
                        .contributeAll(SdkTestFixtures.baseContributions())
                        .tools(List.of(new WeatherTool(), new GeocodeTool()))
                        .build();
                HaifaAgent withoutTools = HaifaAgents.builder(profile)
                        .contributeAll(SdkTestFixtures.baseContributions())
                        .build()) {
            assertThat(withTools.assembly().profile().allowedTools())
                    .containsExactlyInAnyOrder("weather_get", "geocode");
            assertThat(withoutTools.assembly().profile().allowedTools()).isEmpty();
            assertThat(profile.allowedTools()).isEmpty();
        }
    }

    @Test
    void appliesProductPublicToolPolicyDecoratorDuringRuntimeAssembly() {
        AtomicBoolean decorated = new AtomicBoolean();

        try (HaifaAgent ignored = HaifaAgents.builder()
                .product(SdkTestFixtures.profile("personal", Map.of()))
                .contributeAll(SdkTestFixtures.baseContributions())
                .publicToolPolicyDecorator(delegate -> {
                    decorated.set(true);
                    return delegate;
                })
                .build()) {
            assertThat(decorated).isTrue();
        }
    }

    @Test
    void completesMultiRunConversationAndLifecycleCommands() throws Exception {
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator identifiers = () -> "sdk-test-" + ids.incrementAndGet();
        try (HaifaAgent agent = HaifaAgents.builder()
                .product(SdkTestFixtures.profile("personal", Map.of()))
                .contributeAll(SdkTestFixtures.baseContributions())
                .identifierGenerator(identifiers)
                .timeProvider(() -> Instant.parse("2026-07-28T00:00:00Z"))
                .build()) {
            var started = agent.conversations().start(new StartConversationCommand("start-1", "First chat", "hello"));
            var duplicate = agent.conversations().start(new StartConversationCommand("start-1", "First chat", "hello"));

            assertThat(duplicate.sessionId()).isEqualTo(started.sessionId());
            agent.runs().await(started.activeRunId().orElseThrow());
            var idle = agent.conversations().find(started.sessionId()).orElseThrow();
            assertThat(idle.activeRunId()).isEmpty();

            var submitted = agent.conversations()
                    .submit(new SubmitConversationTurnCommand(idle.sessionId(), idle.revision(), "turn-2", "continue"));
            agent.runs().await(submitted.activeRunId().orElseThrow());
            var afterSecondRun = agent.conversations().find(started.sessionId()).orElseThrow();
            assertThat(agent.conversations().turns(started.sessionId()))
                    .extracting("text")
                    .containsExactly("hello", "answer-1", "continue", "answer-2");

            var renamed = agent.conversations()
                    .rename(new RenameConversationCommand(
                            started.sessionId(), afterSecondRun.revision(), "rename-1", "Renamed"));
            var renameRetry = agent.conversations()
                    .rename(new RenameConversationCommand(
                            started.sessionId(), afterSecondRun.revision(), "rename-1", "Renamed"));
            assertThat(renameRetry.displayName()).isEqualTo(renamed.displayName());

            var archived = agent.conversations()
                    .archive(new ChangeConversationStatusCommand(started.sessionId(), renamed.revision(), "archive-1"));
            var archiveRetry = agent.conversations()
                    .archive(new ChangeConversationStatusCommand(started.sessionId(), renamed.revision(), "archive-1"));
            assertThat(archiveRetry.status()).isEqualTo(ConversationStatus.ARCHIVED);
            var restored = agent.conversations()
                    .unarchive(new ChangeConversationStatusCommand(
                            started.sessionId(), archived.revision(), "unarchive-1"));

            assertThat(restored.status()).isEqualTo(ConversationStatus.ACTIVE);
            assertThat(agent.conversations().list(ConversationQuery.active(10)).items())
                    .extracting("sessionId")
                    .containsExactly(started.sessionId());
            assertThat(agent.assembly().profile().productId().value()).isEqualTo("personal");
        }
    }

    @Test
    void callerScopeDoesNotRevealAnotherPrincipalsConversation() throws Exception {
        AtomicInteger ids = new AtomicInteger();
        AtomicReference<SdkCaller> caller =
                new AtomicReference<>(new SdkCaller(new TenantRef("tenant"), new PrincipalRef("alice", "user")));
        try (HaifaAgent agent = HaifaAgents.builder(SdkTestFixtures.profile("personal", Map.of()))
                .contributeAll(SdkTestFixtures.baseContributions())
                .callerProvider(caller::get)
                .identifierGenerator(() -> "scope-test-" + ids.incrementAndGet())
                .timeProvider(() -> Instant.parse("2026-07-28T00:00:00Z"))
                .build()) {
            var conversation =
                    agent.conversations().start(new StartConversationCommand("start", "Private", "secret text"));
            var runId = conversation.activeRunId().orElseThrow();
            agent.runs().await(runId);
            caller.set(new SdkCaller(new TenantRef("tenant"), new PrincipalRef("bob", "user")));

            assertThat(agent.conversations().find(conversation.sessionId())).isEmpty();
            assertThat(agent.conversations().list(ConversationQuery.active(10)).items())
                    .isEmpty();
            assertThatThrownBy(() -> agent.conversations().turns(conversation.sessionId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("CONVERSATION_UNAVAILABLE");
            assertThat(agent.runs().promptDiagnostics(runId).available()).isFalse();
            assertThat(agent.runs().promptDiagnostics(runId).statusCode()).isEqualTo("PROMPT_DIAGNOSTICS_UNAVAILABLE");
        }
    }

    @Test
    void lightweightChatFailsWithStableClosedError() {
        HaifaAgent agent = HaifaAgents.builder(SdkTestFixtures.profile("personal", Map.of()))
                .contributeAll(SdkTestFixtures.baseContributions())
                .build();
        agent.close();

        assertThatThrownBy(() -> agent.chat("hello"))
                .isInstanceOf(HaifaAgentException.class)
                .hasMessage("AGENT_CLOSED");
    }

    @Test
    void cachedConversationServiceFailsWithStableSafeErrorAfterClose() {
        HaifaAgent agent = HaifaAgents.builder(SdkTestFixtures.profile("personal", Map.of()))
                .contributeAll(SdkTestFixtures.baseContributions())
                .build();
        var conversations = agent.conversations();

        agent.close();

        assertThatThrownBy(() -> conversations.list(ConversationQuery.active(10)))
                .isInstanceOf(ConversationException.class)
                .satisfies(failure -> {
                    ConversationException error = (ConversationException) failure;
                    assertThat(error.code()).isEqualTo("AGENT_CLOSED");
                    assertThat(error.operation()).isEqualTo("conversation.list");
                    assertThat(error.correlation()).matches("[0-9a-f]{16}");
                    assertThat(error.getMessage()).isEqualTo("AGENT_CLOSED");
                    assertThat(error.getCause()).isNull();
                });
    }

    public record WeatherRequest(String city) {}

    public record WeatherResponse(String forecast) {}

    private static final class WeatherTool implements JavaTool<WeatherRequest, WeatherResponse> {
        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return JavaToolSpec.builder("weather.get", WeatherRequest.class, WeatherResponse.class)
                    .alias("weather_get")
                    .description("Gets the weather for a city")
                    .pure()
                    .build();
        }

        @Override
        public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
            return new WeatherResponse("Sunny in " + input.city());
        }
    }

    private static final class GeocodeTool implements JavaTool<WeatherRequest, WeatherResponse> {
        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return JavaToolSpec.builder("geocode", WeatherRequest.class, WeatherResponse.class)
                    .pure()
                    .build();
        }

        @Override
        public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
            return new WeatherResponse(input.city());
        }
    }
}
