package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.CodingCompactionResult;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.cli.remote.RemoteCodingSessionClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelImageDetail;
import io.haifa.agent.model.api.ModelImageSource;
import io.haifa.agent.model.openai.AliyunBailianProviderFactory;
import io.haifa.agent.model.openai.OpenAiCompatibleDialects;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real loopback Host + accepted RemoteCodingSessionClient assembly over the existing product/SQLite runtime. */
@Tag("slow")
class CodingHostRemoteClientTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path directory;

    @Test
    void hostAndRemoteClientShareSessionRunModelsCompactionAndReplay() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("remote-assembly"));
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AgentChatModel model = request -> {
            modelStarted.countDown();
            CodingHostTestFixture.await(release);
            return CodingHostTestFixture.response("remote-answer");
        };
        CliConfiguration configuration =
                imageModelConfiguration(CodingHostTestFixture.sqliteConfiguration(directory.resolve("remote.db")));
        try (var agent = CodingHostTestFixture.openAgent(workspace, configuration, model);
                CodingHostServer server = CodingHostServer.start(agent, "127.0.0.1", 0);
                var client = RemoteCodingSessionClient.connect(endpoint(server))) {
            assertThat(client.projectId()).isEqualTo(agent.projectId());
            assertThat(client.info().protocolVersion()).isEqualTo("1");
            assertThat(client.info().principalType()).isEqualTo("local-user");
            assertThat(client.info().clientId()).isNotBlank();
            assertThat(client.info().supports("run-start")).isTrue();

            CodingSessionView created = client.create(client.projectId(), "remote task", "remote-create");
            AgentSessionId sessionId = created.summary().sessionId();
            AgentRunId runId = created.activeRun().orElseThrow().runId();
            assertThat(modelStarted.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .isTrue();

            CountDownLatch terminalEvent = new CountDownLatch(1);
            try (RunEventSubscription subscription = client.subscribe(
                    runId, RunEventCursor.beforeFirst(runId), event -> countIfTerminal(event, terminalEvent))) {
                release.countDown();
                assertThat(terminalEvent.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                        .isTrue();
            }

            AgentRunSnapshot completed = client.findRun(runId).orElseThrow();
            assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(completed.output()).contains("remote-answer");
            assertThat(completed.usage().inputTokens()).isGreaterThanOrEqualTo(0);
            assertThat(completed.usage().outputTokens()).isGreaterThanOrEqualTo(0);
            assertThat(completed.usage().cachedInputTokens()).isGreaterThanOrEqualTo(0);
            assertThat(completed.usage().modelCalls()).isPositive();
            assertThat(completed.usage().toolCalls()).isGreaterThanOrEqualTo(0);
            assertThat(completed.usage().childRuns()).isGreaterThanOrEqualTo(0);
            assertThat(completed.usage().costMinorUnits()).isGreaterThanOrEqualTo(0);
            assertThat(completed.usage().wallTimeMillis()).isGreaterThanOrEqualTo(0);
            AgentRunResult result = completed.result().orElseThrow();
            assertThat(result.outcome()).isEqualTo(AgentRunOutcome.SUCCESS);
            assertThat(result.summary()).isNotBlank();

            CodingSessionView opened = client.open(sessionId);
            assertThat(opened.summary().sessionId()).isEqualTo(sessionId);
            assertThat(opened.model().model().imageInput()).isEmpty();
            CodingSessionView reconciled = client.reconcile(sessionId);
            assertThat(reconciled.summary().sessionId()).isEqualTo(sessionId);
            CodingSessionHistoryPage history = client.history(sessionId, 100);
            assertThat(history.sessionId()).isEqualTo(sessionId);
            assertThat(history.items())
                    .anySatisfy(item -> assertThat(item.body()).contains("remote-answer"));

            RunEventPage replay = client.events(runId, RunEventCursor.beforeFirst(runId), 200);
            assertThat(replay.items()).isNotEmpty();
            for (AgentRunEvent event : replay.items()) {
                assertThat(event.runId()).isEqualTo(runId);
                assertThat(event.sessionId()).isEqualTo(sessionId);
                if (event.payload() instanceof RunEventPayloads.AssistantOutput output) {
                    assertThat(output.generationId()).isNotBlank();
                    assertThat(output.status()).isNotBlank();
                }
            }
            RunEventCursor acknowledged = client.acknowledgeCursor(sessionId, replay.headCursor());
            assertThat(acknowledged).isEqualTo(replay.headCursor());

            List<CodingModelOption> models = client.models();
            assertThat(models).anyMatch(option -> option.imageInput().isEmpty());
            CodingModelOption imageModel = models.stream()
                    .filter(option -> option.imageInput().isPresent())
                    .findFirst()
                    .orElseThrow();
            assertThat(imageModel.id()).isEqualTo("qwen3-vl-plus");
            var profile = imageModel.imageInput().orElseThrow();
            assertThat(profile.allowedSources())
                    .containsExactlyInAnyOrder(ModelImageSource.UPLOAD, ModelImageSource.URL);
            assertThat(profile.supportedMediaTypes()).contains("image/png", "image/jpeg", "image/webp", "image/gif");
            assertThat(profile.maxImagesPerRequest()).isEqualTo(4);
            assertThat(profile.maxBytesPerItem()).isEqualTo(10 * 1024 * 1024L);
            assertThat(profile.maxTotalBytes()).isEqualTo(20 * 1024 * 1024L);
            assertThat(profile.maxUrlCharacters()).isEqualTo(2048);
            assertThat(profile.detailSupported()).isTrue();
            assertThat(profile.allowedDetails())
                    .containsExactlyInAnyOrder(ModelImageDetail.AUTO, ModelImageDetail.HIGH, ModelImageDetail.LOW);

            CodingCompactionResult compaction = client.compact(sessionId, "");
            assertThat(compaction.through()).isNotNull();
            assertThat(compaction.through().value()).isPositive();
            assertThat(compaction.sourceMessageCount()).isGreaterThanOrEqualTo(0);

            assertThat(client.authentication().apiKeyConnectionSupported()).isFalse();
            assertThat(client.authentication().codexConnectionSupported()).isFalse();
            assertThat(client.authentication().antigravityConnectionSupported()).isFalse();
            assertThat(client.authentication().apiKeyProviderId()).isNotBlank();
            assertThat(client.authentication().connectionRequired()).isFalse();
            assertThat(client.authentication().connections()).isEmpty();
            assertThat(client.authentication().providers()).isEmpty();
        }
    }

    @Test
    void observationCloseDoesNotCancelRunAndSecondClientUsesExactRevision() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("remote-observation"));
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AgentChatModel model = request -> {
            if (calls.incrementAndGet() == 1) {
                modelStarted.countDown();
                CodingHostTestFixture.await(release);
                return CodingHostTestFixture.response("survived-observer-close");
            }
            return CodingHostTestFixture.response("unexpected-second-call");
        };
        CliConfiguration configuration = CodingHostTestFixture.sqliteConfiguration(directory.resolve("observer.db"));
        try (var agent = CodingHostTestFixture.openAgent(workspace, configuration, model);
                CodingHostServer server = CodingHostServer.start(agent, "127.0.0.1", 0)) {
            String sessionId;
            String runId;
            try (var first = RemoteCodingSessionClient.connect(endpoint(server))) {
                CodingSessionView created = first.create(first.projectId(), "observer task", "observer-create");
                sessionId = created.summary().sessionId().value();
                runId = created.activeRun().orElseThrow().runId().value();
                assertThat(modelStarted.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                        .isTrue();
                try (RunEventSubscription ignored = first.subscribe(
                        new AgentRunId(runId), RunEventCursor.beforeFirst(new AgentRunId(runId)), event -> {})) {
                    assertThat(ignored.closed()).isFalse();
                }
            }

            try (var second = RemoteCodingSessionClient.connect(endpoint(server))) {
                AgentRunSnapshot stillRunning =
                        second.findRun(new AgentRunId(runId)).orElseThrow();
                assertThat(stillRunning.status().isTerminal()).isFalse();
                CountDownLatch terminalEvent = new CountDownLatch(1);
                try (RunEventSubscription subscription = second.subscribe(
                        new AgentRunId(runId),
                        RunEventCursor.beforeFirst(new AgentRunId(runId)),
                        event -> countIfTerminal(event, terminalEvent))) {
                    release.countDown();
                    assertThat(terminalEvent.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                            .isTrue();
                }
                AgentRunSnapshot completed =
                        second.findRun(new AgentRunId(runId)).orElseThrow();
                assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(completed.output()).contains("survived-observer-close");

                CodingSessionView beforeRename = second.open(new AgentSessionId(sessionId));
                long revision = beforeRename.summary().revision();
                CodingSessionSummary renamed =
                        second.rename(new AgentSessionId(sessionId), "renamed by second client", revision);
                assertThat(renamed.displayName()).isEqualTo("renamed by second client");
                assertThat(renamed.revision()).isEqualTo(revision + 1);
            }
        }
    }

    @Test
    void pendingApprovalAcceptsExactIdentityAndRejectsWithoutWriting() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("remote-approval"));
        Path target = workspace.resolve("rejected.txt");
        AtomicInteger calls = new AtomicInteger();
        AgentChatModel model = request -> switch (calls.incrementAndGet()) {
            case 1 ->
                CodingHostTestFixture.toolResponse(
                        "remote-write", "file_write", java.util.Map.of("path", target.toString(), "content", "no\n"));
            default -> CodingHostTestFixture.response("rejected-done");
        };
        CliConfiguration configuration =
                CodingHostTestFixture.sqliteConfiguration(directory.resolve("approval.db"), ApprovalMode.ASK);
        try (var agent = CodingHostTestFixture.openAgent(workspace, configuration, model);
                CodingHostServer server = CodingHostServer.start(agent, "127.0.0.1", 0);
                var client = RemoteCodingSessionClient.connect(endpoint(server))) {
            CountDownLatch pendingEvent = new CountDownLatch(1);
            CodingSessionView created = client.create(client.projectId(), "approval task", "approval-create");
            AgentSessionId sessionId = created.summary().sessionId();
            AgentRunId runId = created.activeRun().orElseThrow().runId();
            CountDownLatch terminalEvent = new CountDownLatch(1);
            try (RunEventSubscription subscription =
                    client.subscribe(runId, RunEventCursor.beforeFirst(runId), event -> {
                        if (event.payload() instanceof RunEventPayloads.InteractionLifecycle interaction
                                && "PENDING".equals(interaction.state())) {
                            pendingEvent.countDown();
                        }
                        countIfTerminal(event, terminalEvent);
                    })) {
                assertThat(pendingEvent.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                        .isTrue();
                InteractionView pending = client.pendingInteraction(runId).orElseThrow();
                assertThat(pending.sessionId()).isEqualTo(sessionId);
                assertThat(pending.runId()).isEqualTo(runId);
                assertThat(pending.requestId().value()).isNotBlank();
                assertThat(pending.revision()).isGreaterThanOrEqualTo(0);
                assertThat(pending.kind().value()).isEqualTo("approval");
                assertThat(pending.title()).isNotBlank();
                assertThat(pending.safePrompt()).isNotBlank();
                assertThat(pending.allowedActions()).contains(InteractionAction.REJECT);
                assertThat(pending.inputContract()).isNotNull();
                assertThat(pending.target()).isNotNull();
                assertThat(pending.requester()).isNotNull();
                assertThat(pending.consequences()).isNotNull();
                assertThat(client.prepareApproval(pending).orElseThrow().allowForSession())
                        .isFalse();

                InteractionResponseReceipt receipt =
                        client.respond(pending, InteractionAction.REJECT, "reject-remote-approval");
                assertThat(receipt.requestId()).isEqualTo(pending.requestId());
                assertThat(receipt.runId()).isEqualTo(runId);
                assertThat(receipt.status()).isNotNull();
                assertThat(receipt.interactionState()).isIn(InteractionState.RESPONDED, InteractionState.APPLIED);
                assertThat(receipt.revision()).isGreaterThanOrEqualTo(pending.revision());
                assertThat(receipt.runVersion()).isGreaterThanOrEqualTo(0);

                assertThat(terminalEvent.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                        .isTrue();
            }
            assertThat(client.findRun(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(Files.exists(target)).isFalse();
        }
    }

    private static void countIfTerminal(AgentRunEvent event, CountDownLatch terminal) {
        if (event.payload() instanceof RunEventPayloads.RunLifecycle lifecycle
                && AgentRunStatus.valueOf(lifecycle.status()).isTerminal()) {
            terminal.countDown();
        }
    }

    private static URI endpoint(CodingHostServer server) {
        return URI.create("http://127.0.0.1:" + server.port());
    }

    private static CliConfiguration imageModelConfiguration(CliConfiguration base) {
        URI endpoint = new AliyunBailianProviderFactory.ProviderConfiguration(
                        "cli-v1", "testws", null, new CredentialRef("env://HAIFA_TEST_IMAGE_KEY"))
                .endpoint();
        CliConfiguration.Model imageModel = new CliConfiguration.Model(
                "aliyun-bailian",
                "Aliyun Bailian",
                "qwen3-vl-plus",
                endpoint,
                endpoint,
                "env://HAIFA_TEST_IMAGE_KEY",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.ALIYUN_BAILIAN,
                true,
                "testws",
                "cn-beijing",
                "qwen3-vl-plus",
                "Qwen3 VL Plus",
                EnumSet.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.IMAGE_UPLOAD_INPUT,
                        ModelCapability.IMAGE_URL_INPUT),
                131_072,
                8_192);
        List<CliConfiguration.Model> models = new ArrayList<>(base.availableModels());
        models.add(imageModel);
        return CliConfigurationBuilder.from(base)
                .availableModels(List.copyOf(models))
                .build();
    }
}
