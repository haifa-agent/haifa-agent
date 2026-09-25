package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Steer through the public SDK surface only: submit, observe events, await the Run. */
public class AgentRunsSteerTest {
    private static final Duration AWAIT = Duration.ofSeconds(20);

    @Test
    void inputSubmittedWhileTheModelAnswersIsAppliedOnTheNextIterationAndVisibleToTheModel() throws Exception {
        CountDownLatch firstCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<AgentChatRequest> requests = new CopyOnWriteArrayList<>();
        try (HaifaAgent agent = agent("steer-running", request -> {
            requests.add(request);
            if (requests.size() == 1) {
                firstCall.countDown();
                awaitQuietly(release);
                return answer("first answer");
            }
            return answer("revised answer");
        })) {
            AgentRunId runId = agent.conversations()
                    .start(new StartConversationCommand("start-steer", "Steer", "Plan the trip"))
                    .runId();
            assertThat(firstCall.await(10, TimeUnit.SECONDS)).isTrue();

            RunInputCommand steer = new RunInputCommand(runId, "steer-1", "Also cover Hangzhou");
            RunInputResult accepted = agent.runs().submitInput(steer);
            RunInputResult duplicate = agent.runs().submitInput(steer);
            release.countDown();
            var completed = agent.runs().await(runId, AWAIT).orElseThrow();

            assertThat(accepted.status()).isEqualTo(RunInputStatus.ACCEPTED);
            assertThat(accepted.acceptedAt()).isPresent();
            assertThat(duplicate.status()).isEqualTo(RunInputStatus.DUPLICATE);
            assertThat(duplicate.inputId()).isEqualTo(accepted.inputId());
            assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(completed.output()).contains("revised answer");
            assertThat(requests).hasSize(2);
            assertThat(indexOf(requests.get(1).messages(), ModelMessageRole.USER, "Also cover Hangzhou"))
                    .isGreaterThan(indexOf(requests.get(1).messages(), ModelMessageRole.ASSISTANT, "first answer"));
            assertThat(requests.get(1).messages().stream()
                            .filter(message -> message.content().contains("Also cover Hangzhou"))
                            .count())
                    .as("a duplicate submission is applied once")
                    .isEqualTo(1);

            RunInputResult afterwards = agent.runs().submitInput(steer);
            assertThat(afterwards.status()).isEqualTo(RunInputStatus.APPLIED);
            assertThat(afterwards.iteration()).hasValue(2);
            assertThat(afterwards.appliedAt()).isPresent();

            List<AgentRunEvent> events = events(agent, runId);
            assertThat(events)
                    .extracting(AgentRunEvent::eventType)
                    .containsSubsequence("run.input.accepted", "completion.deferred", "run.input.applied");
            assertThat(events)
                    .filteredOn(event -> event.eventType().startsWith("run.input."))
                    .extracting(event -> ((RunEventPayloads.RunInputLifecycle) event.payload()).inputId())
                    .containsOnly(accepted.inputId());
        }
    }

    @Test
    void inputSubmittedDuringAToolCallDoesNotTouchTheToolAndAppliesAfterItReturns() throws Exception {
        AtomicReference<HaifaAgent> agentRef = new AtomicReference<>();
        AtomicReference<AgentRunId> runRef = new AtomicReference<>();
        AtomicReference<RunInputResult> duringTool = new AtomicReference<>();
        AtomicReference<CityRequest> toolInput = new AtomicReference<>();
        List<AgentChatRequest> requests = new CopyOnWriteArrayList<>();
        JavaTool<CityRequest, CityResponse> tool = new JavaTool<>() {
            @Override
            public JavaToolSpec<CityRequest, CityResponse> spec() {
                return JavaToolSpec.builder("city_lookup", CityRequest.class, CityResponse.class)
                        .description("Looks up a city")
                        .pure()
                        .build();
            }

            @Override
            public CityResponse invoke(CityRequest input, JavaToolContext context) {
                duringTool.set(agentRef.get()
                        .runs()
                        .submitInput(new RunInputCommand(runRef.get(), "during-tool", "Prefer trains")));
                toolInput.set(input);
                return new CityResponse("Shanghai is sunny");
            }
        };
        try (HaifaAgent agent = agent(
                "steer-tool",
                request -> {
                    requests.add(request);
                    if (requests.size() == 1) {
                        return new AgentChatResponse(
                                "tool-response",
                                request.model().providerModelId(),
                                "",
                                List.of(new ModelToolCall(
                                        new ProviderToolCallCorrelationId("city-call-1"),
                                        "city_lookup",
                                        Map.of("city", "Shanghai"))),
                                ModelFinishReason.TOOL_CALLS,
                                ModelUsage.unpriced(2, 1),
                                "",
                                Map.of());
                    }
                    return answer("take the train");
                },
                tool)) {
            agentRef.set(agent);
            AgentRunId runId = agent.conversations()
                    .start(new StartConversationCommand("start-tool", "Tool", "How do I travel?"))
                    .runId();
            runRef.set(runId);
            var completed = agent.runs().await(runId, AWAIT).orElseThrow();

            assertThat(completed.status())
                    .as("run error: %s", completed.error())
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(duringTool.get().status()).isEqualTo(RunInputStatus.ACCEPTED);
            assertThat(toolInput.get()).isEqualTo(new CityRequest("Shanghai"));
            List<ModelMessage> second = requests.get(1).messages();
            int toolResult = -1;
            for (int index = 0; index < second.size(); index++) {
                if (second.get(index).role() == ModelMessageRole.TOOL) toolResult = index;
            }
            assertThat(toolResult).isNotNegative();
            assertThat(indexOf(second, ModelMessageRole.USER, "Prefer trains")).isGreaterThan(toolResult);
            assertThat(agent.runs()
                            .submitInput(new RunInputCommand(runId, "during-tool", "Prefer trains"))
                            .iteration())
                    .hasValue(2);
        }
    }

    @Test
    void terminalRunReturnsRejectedWithoutAcceptingTheInput() throws Exception {
        try (HaifaAgent agent = agent("steer-terminal", request -> answer("done"))) {
            AgentRunId runId = agent.conversations()
                    .start(new StartConversationCommand("start-terminal", "Done", "Say done"))
                    .runId();
            assertThat(agent.runs().await(runId, AWAIT).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);

            RunInputResult rejected = agent.runs().submitInput(new RunInputCommand(runId, "late", "too late"));

            assertThat(rejected.status()).isEqualTo(RunInputStatus.REJECTED);
            assertThat(rejected.reasonCode()).contains(RunInputResult.RUN_NOT_ACCEPTING_INPUT);
            assertThat(rejected.acceptedAt()).isEmpty();
            assertThat(events(agent, runId))
                    .noneMatch(event -> event.eventType().startsWith("run.input."));
        }
    }

    @Test
    void cancellingARunSettlesItsAcceptedInputAsRejected() throws Exception {
        CountDownLatch firstCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (HaifaAgent agent = agent("steer-cancel", request -> {
            firstCall.countDown();
            awaitQuietly(release);
            return answer("unused");
        })) {
            AgentRunId runId = agent.conversations()
                    .start(new StartConversationCommand("start-cancel", "Cancel", "Work slowly"))
                    .runId();
            assertThat(firstCall.await(10, TimeUnit.SECONDS)).isTrue();
            RunInputCommand steer = new RunInputCommand(runId, "before-cancel", "never mind");
            assertThat(agent.runs().submitInput(steer).status()).isEqualTo(RunInputStatus.ACCEPTED);

            agent.runs().handle(runId).cancel();
            release.countDown();
            var stopped = agent.runs().await(runId, AWAIT).orElseThrow();

            assertThat(stopped.status()).isEqualTo(AgentRunStatus.CANCELLED);
            RunInputResult settled = agent.runs().submitInput(steer);
            assertThat(settled.status()).isEqualTo(RunInputStatus.REJECTED);
            assertThat(settled.reasonCode()).contains("run-cancelled");
            assertThat(events(agent, runId))
                    .filteredOn(event -> event.eventType().equals("run.input.rejected"))
                    .singleElement()
                    .satisfies(event -> assertThat(
                                    ((RunEventPayloads.RunInputLifecycle) event.payload()).applicationCoordinate())
                            .isEqualTo("run-cancelled"));
        }
    }

    @Test
    void anotherCallersRunIsNotVisibleToSteer() throws Exception {
        AtomicReference<SdkCaller> caller = new AtomicReference<>(SdkCaller.defaultPublicUser());
        try (HaifaAgent agent = SdkTestFixtures.builder("steer-caller")
                .callerProvider(caller::get)
                .build()) {
            AgentRunId runId = agent.conversations()
                    .start(new StartConversationCommand("start-caller", "Mine", "hello"))
                    .runId();
            agent.runs().await(runId, AWAIT);
            caller.set(new SdkCaller(
                    new io.haifa.agent.core.reference.TenantRef("public"),
                    new io.haifa.agent.core.reference.PrincipalRef("someone-else", "user")));

            assertThatThrownBy(() -> agent.runs().submitInput(new RunInputCommand(runId, "foreign", "hijack")))
                    .isInstanceOf(RuntimeContractException.class)
                    .extracting("code")
                    .isEqualTo(RuntimeApiErrorCode.RUN_NOT_FOUND);
        }
    }

    @Test
    void commandRejectsBlankOrOversizedText() {
        AgentRunId runId = new AgentRunId("run-1");
        assertThatThrownBy(() -> new RunInputCommand(runId, " ", "text")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunInputCommand(runId, "key", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunInputCommand(runId, "key", "x".repeat(32_001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    public record CityRequest(String city) {}

    public record CityResponse(String summary) {}

    private static List<AgentRunEvent> events(HaifaAgent agent, AgentRunId runId) {
        return agent.runs()
                .events(runId, RunEventCursor.beforeFirst(runId), 200)
                .items();
    }

    private static int indexOf(List<ModelMessage> messages, ModelMessageRole role, String content) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).role() == role
                    && messages.get(index).content().contains(content)) return index;
        }
        return -1;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static AgentChatResponse answer(String text) {
        return new AgentChatResponse(
                "response-" + Math.abs(text.hashCode()),
                "steer-chat",
                text,
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    private static HaifaAgent agent(
            String productId, Function<AgentChatRequest, AgentChatResponse> behavior, JavaTool<?, ?>... tools) {
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("test"),
                "1.0",
                new ModelDefinitionId("steer-chat"),
                "1.0",
                "steer-chat",
                "test-adapter",
                "1.0",
                new ApiStyleId("test-style"),
                "standard",
                URI.create("https://model.invalid/v1"),
                new CredentialRef("credential:test"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
        AgentChatModel model = behavior::apply;
        HaifaAgentBuilder builder = HaifaAgents.builder(SdkTestFixtures.profile(productId))
                .model(new ModelContribution(
                        Map.of(ModelAdapterCoordinate.from(snapshot), model),
                        snapshot,
                        Map.of(snapshot.modelId().value(), snapshot)))
                .persistence(SdkTestFixtures.persistenceContribution())
                .conversation(SdkTestFixtures.conversationContribution())
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()));
        for (JavaTool<?, ?> tool : tools) builder.tool(tool);
        return builder.build();
    }
}
