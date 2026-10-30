package io.haifa.agent.cli.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingSessionCreateOptions;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventSubscription;
import io.haifa.agent.runtime.api.RunOutputSubscription;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RemoteCodingSessionClientTest {
    private static final ProjectId PROJECT = new ProjectId("project-1");
    private static final AgentSessionId SESSION = new AgentSessionId("session-1");
    private static final AgentRunId RUN = new AgentRunId("run-1");
    private static final long NOW = 1_700_000_000_123L;

    @Test
    void decodesEventAndOutputWireDirectly() throws Exception {
        RemoteWireCodec codec = new RemoteWireCodec(FakeCodingHost.JSON);
        AgentRunEvent event =
                codec.event(FakeCodingHost.JSON.readTree(FakeCodingHost.JSON.writeValueAsString(event())));
        assertThat(event.eventType()).isEqualTo("assistant.message-committed");
        assertThat(event.sessionId()).isEqualTo(SESSION);
        AgentRunOutputEvent output =
                codec.outputEvent(FakeCodingHost.JSON.readTree(FakeCodingHost.JSON.writeValueAsString(output(" "))));
        assertThat(output.textDelta()).isEqualTo(" ");
    }

    @Test
    void connectsWithProtocolContextAndSendsClientHeaderForSubsequentCommands() throws Exception {
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "session/list" ->
                FakeCodingHost.Reply.result(map("sessions", List.of(summary()), "truncated", false, "limit", 10));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                assertThat(client.projectId()).isEqualTo(PROJECT);
                assertThat(client.info().protocolVersion()).isEqualTo("1");
                assertThat(client.info().hostName()).isEqualTo("ca-host");
                assertThat(client.info().clientId()).isEqualTo("client-1");
                assertThat(client.info().supports("event-stream")).isTrue();
                assertThat(client.info().supports("preview")).isFalse();
                assertThat(client.info().unsupported()).contains("preview", "session-consent");

                List<CodingSessionSummary> sessions = client.list(PROJECT, 10);
                assertThat(sessions).hasSize(1);
                assertThat(sessions.getFirst().sessionId()).isEqualTo(SESSION);
                assertThat(sessions.getFirst().queuedCount()).isEqualTo(2);

                List<FakeCodingHost.RpcCall> calls = host.calls();
                assertThat(calls).hasSize(2);
                assertThat(calls.get(0).method()).isEqualTo("initialize");
                assertThat(calls.get(0).clientId()).isNull();
                assertThat(calls.get(1).method()).isEqualTo("session/list");
                assertThat(calls.get(1).clientId()).isEqualTo("client-1");
                assertThat(calls.get(1).params().get("limit").intValue()).isEqualTo(10);
            }
        }
    }

    @Test
    void rejectsUnsupportedProtocolVersion() throws Exception {
        try (FakeCodingHost host = FakeCodingHost.start(call -> {
            Map<String, Object> result = FakeCodingHost.initializeResult("client-1", "project-1");
            result.put("protocolVersion", "2");
            return FakeCodingHost.Reply.result(result);
        })) {
            assertThatThrownBy(() -> RemoteCodingSessionClient.connect(host.uri()))
                    .satisfies(thrown -> assertProductCode(thrown, "REMOTE_PROTOCOL_INCOMPATIBLE"));
        }
    }

    @Test
    void rejectsMissingRequiredCapabilityAndEnabledUnsupportedCapability() throws Exception {
        try (FakeCodingHost missing = FakeCodingHost.start(call -> {
                    Map<String, Object> result = FakeCodingHost.initializeResult("client-1", "project-1");
                    capabilities(result).remove("output-stream");
                    return FakeCodingHost.Reply.result(result);
                });
                FakeCodingHost enabled = FakeCodingHost.start(call -> {
                    Map<String, Object> result = FakeCodingHost.initializeResult("client-1", "project-1");
                    capabilities(result).put("preview", true);
                    return FakeCodingHost.Reply.result(result);
                })) {
            assertThatThrownBy(() -> RemoteCodingSessionClient.connect(missing.uri()))
                    .satisfies(thrown -> assertProductCode(thrown, "REMOTE_PROTOCOL_INCOMPATIBLE"));
            assertThatThrownBy(() -> RemoteCodingSessionClient.connect(enabled.uri()))
                    .satisfies(thrown -> assertProductCode(thrown, "REMOTE_PROTOCOL_INCOMPATIBLE"));
        }
    }

    @Test
    void decodesRepresentativeSessionViewRunAndInteractionFields() throws Exception {
        Map<String, Object> run = run();
        Map<String, Object> view = map(
                "session",
                summary(),
                "history",
                map("sessionId", SESSION.value(), "items", List.of(), "earlierHistoryAvailable", false),
                "activeRun",
                run,
                "pendingInteraction",
                interaction(),
                "eventCursor",
                cursor(RUN.value(), 3),
                "outputCursor",
                0,
                "configurationDigest",
                "sha256:config",
                "productProfileRef",
                "profile-1",
                "model",
                map("model", model(), "revision", 2, "available", true),
                "activeRunTaskSummary",
                "Fix the build",
                "previewAvailable",
                false);
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "session/open" -> FakeCodingHost.Reply.result(view);
            case "run/get" -> FakeCodingHost.Reply.result(runWithSession());
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                CodingSessionView loaded = client.open(SESSION);
                assertThat(loaded.summary().displayName()).isEqualTo("Fix the build");
                assertThat(loaded.summary().activeRunId()).contains(RUN);
                assertThat(loaded.summary().lastActivityAt().toEpochMilli()).isEqualTo(NOW);
                assertThat(loaded.eventCursor()).hasValueSatisfying(value -> assertThat(value.exclusiveSequence())
                        .hasValue(3));
                assertThat(loaded.configurationDigest()).isEqualTo("sha256:config");
                assertThat(loaded.productProfileRef()).isEqualTo("profile-1");
                assertThat(loaded.activeRunTaskSummary()).contains("Fix the build");
                assertThat(loaded.model().available()).isTrue();
                assertThat(loaded.model().model().id()).isEqualTo("model-1");
                assertThat(loaded.model()
                                .model()
                                .controls()
                                .responseMode()
                                .recommendedValue()
                                .name())
                        .isEqualTo("RECOMMENDED");

                AgentRunSnapshot snapshot = loaded.activeRun().orElseThrow();
                assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(snapshot.output()).contains("final answer");
                assertThat(snapshot.usage().inputTokens()).isEqualTo(11);
                assertThat(snapshot.result().orElseThrow().summary()).isEqualTo("done");
                assertThat(snapshot.result().orElseThrow().warnings()).containsExactly("safe warning");
                assertThat(snapshot.result().orElseThrow().artifacts()).hasSize(1);

                assertThat(loaded.pendingInteraction()).hasValueSatisfying(pending -> {
                    assertThat(pending.requestId().value()).isEqualTo("interaction-1");
                    assertThat(pending.allowedActions())
                            .containsExactly(InteractionAction.APPROVE, InteractionAction.REJECT);
                    assertThat(pending.approvalPresentation().orElseThrow().risk())
                            .contains("LOW");
                    assertThat(pending.inputContract().options()).isEmpty();
                });

                AgentRunSnapshot fetched = client.findRun(RUN).orElseThrow();
                assertThat(fetched).isEqualTo(snapshot);
            }
        }
    }

    @Test
    void preservesWhitespaceOutputAndDeliversEventAndOutputFrames() throws Exception {
        String eventFrame = "event: run-event\ndata: " + FakeCodingHost.JSON.writeValueAsString(event()) + "\n\n";
        String outputFrame = "event: output\ndata: " + FakeCodingHost.JSON.writeValueAsString(output(" ")) + "\n\n";
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "run/start" ->
                FakeCodingHost.Reply.result(map(
                        "operation",
                        "submit-turn",
                        "sessionId",
                        SESSION.value(),
                        "runId",
                        RUN.value(),
                        "replayed",
                        false));
            case "events/subscribe" ->
                FakeCodingHost.Reply.result(map(
                        "subscriptionId",
                        "sub-events",
                        "sessionId",
                        SESSION.value(),
                        "runId",
                        RUN.value(),
                        "type",
                        "run-event"));
            case "output/subscribe" ->
                FakeCodingHost.Reply.result(map(
                        "subscriptionId",
                        "sub-output",
                        "sessionId",
                        SESSION.value(),
                        "runId",
                        RUN.value(),
                        "type",
                        "output"));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            host.script("sub-events", List.of(eventFrame), false);
            host.script("sub-output", List.of(outputFrame), false);
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                client.submit(SESSION, "do work", "start-key");
                BlockingQueue<AgentRunEvent> events = new LinkedBlockingQueue<>();
                BlockingQueue<AgentRunOutputEvent> outputs = new LinkedBlockingQueue<>();
                RunEventSubscription eventSubscription = client.subscribe(RUN, null, events::add);
                RunOutputSubscription outputSubscription = client.subscribeOutput(RUN, null, outputs::add);

                AgentRunEvent event = events.poll(5, TimeUnit.SECONDS);
                AgentRunOutputEvent output = outputs.poll(5, TimeUnit.SECONDS);
                assertThat(event).isNotNull();
                assertThat(event.eventType()).isEqualTo("assistant.message-committed");
                assertThat(event.sessionId()).isEqualTo(SESSION);
                assertThat(output).isNotNull();
                assertThat(output.textDelta()).isEqualTo(" ");
                assertThat(output.sequence()).isEqualTo(1);

                eventSubscription.close();
                outputSubscription.close();
                assertThat(eventSubscription.closed()).isTrue();
                assertThat(host.calls("run/cancel")).isEmpty();
            }
        }
    }

    @Test
    void surfacesSseTupleMismatchOnSubsequentReconcileWithoutCancellingRun() throws Exception {
        String mismatch = "event: run-event\ndata: "
                + FakeCodingHost.JSON.writeValueAsString(eventWithSession("other-session")) + "\n\n";
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "run/start" ->
                FakeCodingHost.Reply.result(map(
                        "operation",
                        "submit-turn",
                        "sessionId",
                        SESSION.value(),
                        "runId",
                        RUN.value(),
                        "replayed",
                        false));
            case "events/subscribe" ->
                FakeCodingHost.Reply.result(map(
                        "subscriptionId",
                        "sub-events",
                        "sessionId",
                        SESSION.value(),
                        "runId",
                        RUN.value(),
                        "type",
                        "run-event"));
            case "session/reconcile" -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            host.script("sub-events", List.of(mismatch), false);
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                client.submit(SESSION, "do work", "start-key");
                RunEventSubscription subscription = client.subscribe(RUN, null, ignored -> {});
                assertThat(host.awaitUnsubscribe("sub-events")).isTrue();
                assertThatThrownBy(() -> client.reconcile(SESSION))
                        .satisfies(thrown -> assertProductCode(thrown, "REMOTE_EVENT_TARGET_MISMATCH"));
                assertThat(host.calls("run/cancel")).isEmpty();
            }
        }
    }

    @Test
    void doesNotRetryAnAcceptedWriteAfterConnectionLoss() throws Exception {
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "run/start" -> FakeCodingHost.Reply.dropped();
            case "session/list" ->
                FakeCodingHost.Reply.result(map("sessions", List.of(), "truncated", false, "limit", 10));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                assertThatThrownBy(() -> client.submit(SESSION, "do work", "stable-key"))
                        .satisfies(thrown -> assertProductCode(thrown, "REMOTE_OUTCOME_UNKNOWN"));
                assertThat(host.calls("run/start")).hasSize(1);
                assertThat(client.list(PROJECT, 10)).isEmpty();
            }
        }
    }

    @Test
    void strictTypeFailuresBecomeProtocolIncompatibility() throws Exception {
        Map<String, Object> invalid = summary();
        invalid.put("revision", "7");
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "session/list" ->
                FakeCodingHost.Reply.result(map("sessions", List.of(invalid), "truncated", false, "limit", 10));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                assertThatThrownBy(() -> client.list(PROJECT, 10))
                        .satisfies(thrown -> assertProductCode(thrown, "REMOTE_PROTOCOL_INCOMPATIBLE"));
            }
        }
    }

    @Test
    void sendsExactIdentitiesAndBusinessKeys() throws Exception {
        Map<String, Object> view = map(
                "session",
                summary(),
                "history",
                map("sessionId", SESSION.value(), "items", List.of(), "earlierHistoryAvailable", false),
                "activeRun",
                run(),
                "pendingInteraction",
                null,
                "eventCursor",
                cursor(RUN.value(), 3),
                "outputCursor",
                0,
                "configurationDigest",
                "sha256:config",
                "productProfileRef",
                "profile-1",
                "model",
                map("model", model(), "revision", 2, "available", true),
                "activeRunTaskSummary",
                null,
                "previewAvailable",
                false);
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "session/create" -> FakeCodingHost.Reply.result(view);
            case "run/steer" ->
                FakeCodingHost.Reply.result(map(
                        "inputId",
                        "input-1",
                        "runId",
                        RUN.value(),
                        "status",
                        "ACCEPTED",
                        "acceptedAt",
                        NOW,
                        "appliedAt",
                        null,
                        "reasonCode",
                        null));
            case "run/followUp" ->
                FakeCodingHost.Reply.result(map(
                        "followUpId",
                        "follow-1",
                        "sessionId",
                        SESSION.value(),
                        "status",
                        "PENDING",
                        "sequence",
                        1,
                        "revision",
                        0,
                        "dispatchedRunId",
                        null));
            case "run/cancel" ->
                FakeCodingHost.Reply.result(
                        map("commandId", "command-1", "runId", RUN.value(), "status", "ACCEPTED", "runVersion", 4));
            case "session/selectModel" ->
                FakeCodingHost.Reply.result(map("model", model(), "revision", 3, "available", true));
            case "session/ackCursor" -> FakeCodingHost.Reply.result(cursor(RUN.value(), 5));
            case "session/restoreQueued" ->
                FakeCodingHost.Reply.result(map(
                        "followUpId",
                        "follow-1",
                        "sessionId",
                        SESSION.value(),
                        "message",
                        "later",
                        "attachments",
                        List.of(map("assetId", "asset-1", "mimeType", "text/plain", "filename", "a.txt")),
                        "revision",
                        3));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                client.create(PROJECT, "first", "create-key", CodingSessionCreateOptions.withInitialModel("model-9"));
                client.steer(SESSION, RUN, "more", "steer-key");
                client.enqueueFollowUp(SESSION, RUN, "later", "follow-key");
                client.cancel(SESSION, RUN, "cancel-key");
                client.selectModel(SESSION, "model-9", 3, "model-key");
                client.acknowledgeCursor(SESSION, new RunEventCursor(RUN, "1", OptionalLong.of(4)));
                client.restore(SESSION, "follow-1", 2L);

                assertThat(host.calls("session/create")
                                .getFirst()
                                .params()
                                .get("modelId")
                                .textValue())
                        .isEqualTo("model-9");
                assertThat(host.calls("session/create")
                                .getFirst()
                                .params()
                                .get("idempotencyKey")
                                .textValue())
                        .isEqualTo("create-key");
                assertThat(host.calls("run/steer")
                                .getFirst()
                                .params()
                                .get("runId")
                                .textValue())
                        .isEqualTo(RUN.value());
                assertThat(host.calls("run/steer")
                                .getFirst()
                                .params()
                                .get("idempotencyKey")
                                .textValue())
                        .isEqualTo("steer-key");
                assertThat(host.calls("run/followUp")
                                .getFirst()
                                .params()
                                .get("runId")
                                .textValue())
                        .isEqualTo(RUN.value());
                assertThat(host.calls("run/cancel")
                                .getFirst()
                                .params()
                                .get("runId")
                                .textValue())
                        .isEqualTo(RUN.value());
                assertThat(host.calls("run/cancel")
                                .getFirst()
                                .params()
                                .get("idempotencyKey")
                                .textValue())
                        .isEqualTo("cancel-key");
                assertThat(host.calls("session/selectModel")
                                .getFirst()
                                .params()
                                .get("expectedRevision")
                                .longValue())
                        .isEqualTo(3);
                assertThat(host.calls("session/ackCursor")
                                .getFirst()
                                .params()
                                .get("cursor")
                                .get("sequence")
                                .longValue())
                        .isEqualTo(4);
                assertThat(host.calls("session/restoreQueued")
                                .getFirst()
                                .params()
                                .get("expectedRevision")
                                .longValue())
                        .isEqualTo(2);
            }
        }
    }

    @Test
    void disabledRemoteOperationsFailBeforeAnyRequest() throws Exception {
        try (FakeCodingHost host = FakeCodingHost.start(call -> {
            if (call.method().equals("initialize")) {
                return FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            }
            return FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                assertThatThrownBy(() -> client.cancel(SESSION, "legacy-key"))
                        .isInstanceOf(UnsupportedOperationException.class);
                assertThatThrownBy(() -> client.allowForSession(null, "session-key"))
                        .isInstanceOf(UnsupportedOperationException.class);
                assertThatThrownBy(() -> client.resetSessionApproval(SESSION))
                        .isInstanceOf(UnsupportedOperationException.class);
                assertThatThrownBy(() -> client.authentication().saveApiKey("deepseek", new char[] {'s'}))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(RemoteCodingAuthenticationClient.CREDENTIAL_WRITE_UNSUPPORTED);
                assertThatThrownBy(() -> client.authentication().loginCodexBrowser())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(RemoteCodingAuthenticationClient.LOGIN_UNSUPPORTED);
                assertThat(client.authentication().apiKeyConnectionSupported()).isFalse();
                assertThat(client.authentication().codexConnectionSupported()).isFalse();
                assertThat(client.authentication().antigravityConnectionSupported())
                        .isFalse();
                assertThat(host.calls()).hasSize(1);
                assertThat(host.calls("run/cancel")).isEmpty();
            }
        }
    }

    @Test
    void safeErrorsNeverLeakHostMessagesOrMalformedBodies() throws Exception {
        String canary = "SECRET_CANARY_DO_NOT_LEAK";
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "session/list" -> FakeCodingHost.Reply.error("SESSION_NOT_FOUND", canary);
            case "session/history" ->
                FakeCodingHost.Reply.result(
                        map("sessionId", canary, "items", canary, "earlierHistoryAvailable", false));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                assertThatThrownBy(() -> client.list(PROJECT, 10)).satisfies(thrown -> {
                    assertProductCode(thrown, "SESSION_NOT_FOUND");
                    assertThat(thrown.getMessage()).doesNotContain(canary);
                });
                assertThatThrownBy(() -> client.history(SESSION, 10)).satisfies(thrown -> {
                    assertProductCode(thrown, "REMOTE_PROTOCOL_INCOMPATIBLE");
                    assertThat(thrown.getMessage()).doesNotContain(canary);
                });
            }
        }
    }

    @Test
    void readTransportLossBecomesSafeUnavailableCode() throws Exception {
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "session/list" -> FakeCodingHost.Reply.dropped();
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                assertThatThrownBy(() -> client.list(PROJECT, 10))
                        .satisfies(thrown -> assertProductCode(thrown, "REMOTE_TRANSPORT_UNAVAILABLE"));
            }
        }
    }

    @Test
    void rejectsNonLoopbackAndAmbiguousUris() {
        for (String value : List.of(
                "http://example.com:17861",
                "https://127.0.0.1:17861",
                "http://user:secret@127.0.0.1:17861",
                "http://127.0.0.1:17861?token=x",
                "http://127.0.0.1:17861#fragment",
                "http://127.0.0.1:17861/path",
                "http://127.0.0.1")) {
            assertThatThrownBy(() -> RemoteCodingSessionClient.connect(URI.create(value)))
                    .satisfies(thrown -> assertProductCode(thrown, "REMOTE_URI_REJECTED"));
        }
    }

    @Test
    void lazilyResolvesHistoricalRunBindingFromRunGet() throws Exception {
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "run/get" -> FakeCodingHost.Reply.result(map("runId", RUN.value(), "sessionId", SESSION.value()));
            case "events/page" ->
                FakeCodingHost.Reply.result(map(
                        "items",
                        List.of(),
                        "nextCursor",
                        cursor(RUN.value(), 1),
                        "headCursor",
                        cursor(RUN.value(), 1),
                        "hasMore",
                        false));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                assertThat(client.events(RUN, null, 10).items()).isEmpty();
                assertThat(host.calls("run/get")).hasSize(1);
                assertThat(host.calls("events/page")
                                .getFirst()
                                .params()
                                .get("sessionId")
                                .textValue())
                        .isEqualTo(SESSION.value());
            }
        }
    }

    @Test
    void findRunCachesHistoricalBindingAcrossSessionSwitches() throws Exception {
        Map<String, Object> snapshot = run();
        snapshot.put("sessionId", SESSION.value());
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "run/get" -> FakeCodingHost.Reply.result(snapshot);
            case "events/page" ->
                FakeCodingHost.Reply.result(map(
                        "items",
                        List.of(),
                        "nextCursor",
                        cursor(RUN.value(), 1),
                        "headCursor",
                        cursor(RUN.value(), 1),
                        "hasMore",
                        false));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                client.findRun(RUN).orElseThrow();
                client.events(RUN, null, 10);
                assertThat(host.calls("run/get")).hasSize(1);
            }
        }
    }

    @Test
    void missingHistoricalRunBindingIsProtocolIncompatible() throws Exception {
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "run/get" -> FakeCodingHost.Reply.result(run());
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                assertThatThrownBy(() -> client.events(RUN, null, 10))
                        .satisfies(thrown -> assertProductCode(thrown, "REMOTE_PROTOCOL_INCOMPATIBLE"));
            }
        }
    }

    @Test
    void coversRemainingProductSurfaceMethods() throws Exception {
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "model/list" -> FakeCodingHost.Reply.result(map("models", List.of(model())));
            case "session/history" ->
                FakeCodingHost.Reply.result(map(
                        "sessionId",
                        SESSION.value(),
                        "items",
                        List.of(map(
                                "id",
                                "message-1",
                                "kind",
                                "USER",
                                "title",
                                "User",
                                "body",
                                "Hello",
                                "status",
                                "COMMITTED",
                                "sequence",
                                1,
                                "createdAt",
                                NOW)),
                        "earlierHistoryAvailable",
                        false));
            case "workspace/paths" -> FakeCodingHost.Reply.result(map("paths", List.of("src", "docs")));
            case "workspace/list" ->
                FakeCodingHost.Reply.result(map(
                        "workspaces",
                        List.of(map(
                                "workspaceRef",
                                "ws-1",
                                "safeDisplayName",
                                "Project",
                                "mode",
                                "DEVELOP",
                                "status",
                                "ACTIVE",
                                "revocable",
                                true))));
            case "resources/loaded" -> FakeCodingHost.Reply.result(map("resources", List.of("Loaded resources: none")));
            case "resources/reload" ->
                FakeCodingHost.Reply.result(map("resources", List.of("Reloaded resources: one")));
            case "session/export" ->
                FakeCodingHost.Reply.result(
                        map("logicalPath", "exports/session-1.jsonl", "messageCount", 2, "schemaVersion", "1"));
            case "shell/plan" ->
                FakeCodingHost.Reply.result(map(
                        "token",
                        "token-1",
                        "sessionId",
                        SESSION.value(),
                        "safeCommand",
                        "git status",
                        "includeInContext",
                        true,
                        "state",
                        "READY",
                        "reasonCode",
                        "OK"));
            case "shell/execute" ->
                FakeCodingHost.Reply.result(map(
                        "processState",
                        "EXITED",
                        "exitCode",
                        0,
                        "safeSummary",
                        "clean",
                        "outputReference",
                        null,
                        "truncated",
                        false,
                        "includedInContext",
                        true));
            case "shell/discard" -> FakeCodingHost.Reply.result(map("discarded", true));
            case "session/queuedMessages" ->
                FakeCodingHost.Reply.result(map(
                        "items",
                        List.of(map(
                                "followUpId",
                                "follow-1",
                                "sessionId",
                                SESSION.value(),
                                "summary",
                                "later",
                                "sequence",
                                1,
                                "revision",
                                0))));
            case "interaction/get" -> FakeCodingHost.Reply.result(interaction());
            case "approval/prepare" ->
                FakeCodingHost.Reply.result(map("interaction", interaction(), "allowForSession", true));
            case "interaction/respond" ->
                FakeCodingHost.Reply.result(map(
                        "responseId",
                        "response-1",
                        "requestId",
                        "interaction-1",
                        "runId",
                        RUN.value(),
                        "status",
                        "NEWLY_ACCEPTED",
                        "interactionState",
                        "APPLIED",
                        "revision",
                        5,
                        "runVersion",
                        6));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                assertThat(client.models()).hasSize(1);
                assertThat(client.history(SESSION, 10).items()).hasSize(1);
                assertThat(client.logicalPaths()).containsExactly("src", "docs");
                assertThat(client.workspaces()).hasSize(1);
                assertThat(client.reloadResources()).containsExactly("Reloaded resources: one");
                assertThat(client.export(SESSION, "exports/session-1.jsonl").messageCount())
                        .isEqualTo(2);
                assertThat(client.planShell(SESSION, "git status", true).state().name())
                        .isEqualTo("READY");
                assertThat(client.executeShell("token-1", true).processState()).isEqualTo("EXITED");
                client.discardShell("token-1");
                assertThat(client.restorableMessages(SESSION, 10)).hasSize(1);

                InteractionView pending = client.pendingInteraction(RUN).orElseThrow();
                var prompt = client.prepareApproval(pending).orElseThrow();
                assertThat(prompt.allowForSession()).isFalse();
                assertThat(prompt.sessionApprovalUnavailableReason()).contains("REMOTE_SESSION_CONSENT_UNSUPPORTED");
                var receipt = client.respond(
                        pending, InteractionAction.APPROVE, List.of(new TextPart("yes", "text/plain")), "respond-key");
                assertThat(receipt.runId()).isEqualTo(RUN);
                assertThat(receipt.status().name()).isEqualTo("NEWLY_ACCEPTED");

                var inputs =
                        host.calls("interaction/respond").getFirst().params().get("inputs");
                assertThat(inputs).hasSize(1);
                assertThat(inputs.get(0).get("type").textValue()).isEqualTo("text");
                assertThat(inputs.get(0).get("text").textValue()).isEqualTo("yes");
                assertThat(host.calls("interaction/respond")
                                .getFirst()
                                .params()
                                .get("idempotencyKey")
                                .textValue())
                        .isEqualTo("respond-key");
            }
        }
    }

    @Test
    void recoverableSseEndAllowsReconcileAndResubscribe() throws Exception {
        var subscriptionIds = new java.util.concurrent.atomic.AtomicInteger();
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "run/start" ->
                FakeCodingHost.Reply.result(map(
                        "operation",
                        "submit-turn",
                        "sessionId",
                        SESSION.value(),
                        "runId",
                        RUN.value(),
                        "replayed",
                        false));
            case "events/subscribe" ->
                FakeCodingHost.Reply.result(map(
                        "subscriptionId",
                        "sub-events-" + subscriptionIds.incrementAndGet(),
                        "sessionId",
                        SESSION.value(),
                        "runId",
                        RUN.value(),
                        "type",
                        "run-event"));
            case "events/unsubscribe" -> FakeCodingHost.Reply.result(map("closed", true));
            case "session/reconcile" -> FakeCodingHost.Reply.result(minimalView());
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            host.script("sub-events-1", List.of(), true);
            host.script("sub-events-2", List.of(), true);
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                client.submit(SESSION, "do work", "start-key");
                client.subscribe(RUN, null, ignored -> {});
                assertThat(host.awaitUnsubscribe("sub-events-1")).isTrue();
                assertThat(client.reconcile(SESSION).summary().sessionId()).isEqualTo(SESSION);
                client.subscribe(RUN, null, ignored -> {});
                assertThat(host.awaitUnsubscribe("sub-events-2")).isTrue();
                assertThat(host.calls("run/cancel")).isEmpty();
            }
        }
    }

    @Test
    void compactAndImageInputProfileUseAdditiveHostFields() throws Exception {
        Map<String, Object> modelWithProfile = model();
        modelWithProfile.put("imageInput", true);
        modelWithProfile.put("imageInputProfile", imageInputProfile());
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "session/compact" ->
                FakeCodingHost.Reply.result(map(
                        "summaryReference",
                        "summary-1",
                        "summaryVersion",
                        2,
                        "sourceMessageCount",
                        5,
                        "estimatedTokens",
                        120,
                        "through",
                        map("value", 9)));
            case "model/list" -> FakeCodingHost.Reply.result(map("models", List.of(modelWithProfile)));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                var compaction = client.compact(SESSION, "compress");
                assertThat(compaction.summaryReference()).contains("summary-1");
                assertThat(compaction.summaryVersion()).isEqualTo(2);
                assertThat(compaction.sourceMessageCount()).isEqualTo(5);
                assertThat(compaction.estimatedTokens()).isEqualTo(120);
                assertThat(compaction.through().value()).isEqualTo(9);
                assertThat(host.calls("session/compact")
                                .getFirst()
                                .params()
                                .get("sessionId")
                                .textValue())
                        .isEqualTo(SESSION.value());
                assertThat(host.calls("session/compact")
                                .getFirst()
                                .params()
                                .get("instruction")
                                .textValue())
                        .isEqualTo("compress");

                CodingModelOption model = client.models().getFirst();
                var profile = model.imageInput().orElseThrow();
                assertThat(profile.allowedSources().stream().map(Enum::name).toList())
                        .contains("UPLOAD", "URL");
                assertThat(profile.supportedMediaTypes()).containsExactlyInAnyOrder("image/png", "image/jpeg");
                assertThat(profile.maxImagesPerRequest()).isEqualTo(4);
                assertThat(profile.allowedDetails().stream().map(Enum::name).toList())
                        .contains("AUTO", "LOW", "HIGH");
            }
        }
    }

    @Test
    void rejectsContentTypeSuffixAndTrailingRpcDocument() throws Exception {
        try (FakeCodingHost suffix = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "session/list" ->
                FakeCodingHost.Reply.result(map("sessions", List.of(), "truncated", false, "limit", 10))
                        .contentType("application/jsonevil");
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(suffix.uri())) {
                assertThatThrownBy(() -> client.list(PROJECT, 10))
                        .satisfies(thrown -> assertProductCode(thrown, "REMOTE_PROTOCOL_INCOMPATIBLE"));
            }
        }

        try (FakeCodingHost trailing = FakeCodingHost.start(call -> {
            if (call.method().equals("initialize")) {
                return FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            }
            String id = call.request().get("id").textValue();
            return FakeCodingHost.Reply.raw("{\"jsonrpc\":\"2.0\",\"id\":\""
                    + id
                    + "\",\"result\":{\"sessions\":[],\"truncated\":false,\"limit\":10}} {\"extra\":1}");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(trailing.uri())) {
                assertThatThrownBy(() -> client.list(PROJECT, 10))
                        .satisfies(thrown -> assertProductCode(thrown, "REMOTE_PROTOCOL_INCOMPATIBLE"));
            }
        }
    }

    @Test
    void rejectsTrailingSseDocument() throws Exception {
        String frame =
                "event: run-event\ndata: " + FakeCodingHost.JSON.writeValueAsString(event()) + " {\"extra\":1}\n\n";
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "run/start" ->
                FakeCodingHost.Reply.result(map(
                        "operation",
                        "submit-turn",
                        "sessionId",
                        SESSION.value(),
                        "runId",
                        RUN.value(),
                        "replayed",
                        false));
            case "events/subscribe" ->
                FakeCodingHost.Reply.result(map(
                        "subscriptionId",
                        "sub-events",
                        "sessionId",
                        SESSION.value(),
                        "runId",
                        RUN.value(),
                        "type",
                        "run-event"));
            case "events/unsubscribe" -> FakeCodingHost.Reply.result(map("closed", true));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            host.script("sub-events", List.of(frame), false);
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                client.submit(SESSION, "do work", "start-key");
                client.subscribe(RUN, null, ignored -> {});
                assertThat(host.awaitUnsubscribe("sub-events")).isTrue();
                assertThatThrownBy(() -> client.reconcile(SESSION))
                        .satisfies(thrown -> assertProductCode(thrown, "REMOTE_PROTOCOL_INCOMPATIBLE"));
            }
        }
    }

    @Test
    void rejectsImageInputPresenceMismatch() throws Exception {
        Map<String, Object> trueWithoutProfile = model();
        trueWithoutProfile.put("imageInput", true);
        trueWithoutProfile.put("imageInputProfile", null);

        Map<String, Object> falseWithProfile = model();
        falseWithProfile.put("imageInput", false);
        falseWithProfile.put("imageInputProfile", imageInputProfile());

        Map<String, Object> missingProfile = model();
        missingProfile.remove("imageInputProfile");

        for (Map<String, Object> invalid : List.of(trueWithoutProfile, falseWithProfile, missingProfile)) {
            try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
                case "initialize" ->
                    FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
                case "model/list" -> FakeCodingHost.Reply.result(map("models", List.of(invalid)));
                default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
            })) {
                try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                    assertThatThrownBy(client::models)
                            .satisfies(thrown -> assertProductCode(thrown, "REMOTE_PROTOCOL_INCOMPATIBLE"));
                }
            }
        }
    }

    private static Map<String, Object> imageInputProfile() {
        return map(
                "allowedSources",
                List.of("UPLOAD", "URL"),
                "supportedMediaTypes",
                List.of("image/png", "image/jpeg"),
                "maxImagesPerRequest",
                4,
                "maxBytesPerItem",
                10_485_760L,
                "maxTotalBytes",
                20_971_520L,
                "maxUrlCharacters",
                2_048,
                "detailSupported",
                true,
                "allowedDetails",
                List.of("AUTO", "LOW", "HIGH"));
    }

    @Test
    void rejectedSteerAndCancelReceiptsAreNotReportedAsSuccess() throws Exception {
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "run/steer" ->
                FakeCodingHost.Reply.result(map(
                        "inputId",
                        "input-1",
                        "runId",
                        RUN.value(),
                        "status",
                        "REJECTED",
                        "acceptedAt",
                        NOW,
                        "appliedAt",
                        null,
                        "reasonCode",
                        "RUN_VERSION_CONFLICT"));
            case "run/cancel" ->
                FakeCodingHost.Reply.result(
                        map("commandId", "command-1", "runId", RUN.value(), "status", "REJECTED", "runVersion", 1));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                assertThatThrownBy(() -> client.steer(SESSION, RUN, "more", "steer-key"))
                        .satisfies(thrown -> assertProductCode(thrown, "RUN_VERSION_CONFLICT"));
                assertThatThrownBy(() -> client.cancel(SESSION, RUN, "cancel-key"))
                        .satisfies(thrown -> assertProductCode(thrown, "RUN_CANCEL_REJECTED"));
            }
        }
    }

    @Test
    void misboundSessionOrReceiptIdentityIsRejected() throws Exception {
        Map<String, Object> misboundView = minimalView();
        @SuppressWarnings("unchecked")
        Map<String, Object> misboundSummary = (Map<String, Object>) misboundView.get("session");
        misboundSummary.put("sessionId", "other-session");
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "session/open" -> FakeCodingHost.Reply.result(misboundView);
            case "run/steer" ->
                FakeCodingHost.Reply.result(map(
                        "inputId",
                        "input-1",
                        "runId",
                        "other-run",
                        "status",
                        "ACCEPTED",
                        "acceptedAt",
                        NOW,
                        "appliedAt",
                        null,
                        "reasonCode",
                        null));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                assertThatThrownBy(() -> client.open(SESSION))
                        .satisfies(thrown -> assertProductCode(thrown, "REMOTE_PROTOCOL_INCOMPATIBLE"));
                assertThatThrownBy(() -> client.steer(SESSION, RUN, "more", "steer-key"))
                        .satisfies(thrown -> assertProductCode(thrown, "REMOTE_PROTOCOL_INCOMPATIBLE"));
            }
        }
    }

    @Test
    void unterminatedSseLineIsRejectedBeforeUnboundedAllocation() throws Exception {
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "run/start" ->
                FakeCodingHost.Reply.result(map(
                        "operation",
                        "submit-turn",
                        "sessionId",
                        SESSION.value(),
                        "runId",
                        RUN.value(),
                        "replayed",
                        false));
            case "events/subscribe" ->
                FakeCodingHost.Reply.result(map(
                        "subscriptionId",
                        "sub-events",
                        "sessionId",
                        SESSION.value(),
                        "runId",
                        RUN.value(),
                        "type",
                        "run-event"));
            case "events/unsubscribe" -> FakeCodingHost.Reply.result(map("closed", true));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            host.script("sub-events", List.of("event: run-event\ndata: " + "a".repeat(300 * 1024)), false);
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                client.submit(SESSION, "do work", "start-key");
                RunEventSubscription subscription = client.subscribe(RUN, null, ignored -> {});
                assertThat(host.awaitUnsubscribe("sub-events")).isTrue();
                assertThatThrownBy(() -> client.reconcile(SESSION))
                        .satisfies(thrown -> assertProductCode(thrown, "REMOTE_PROTOCOL_INCOMPATIBLE"));
                assertThat(host.calls("run/cancel")).isEmpty();
            }
        }
    }

    @Test
    void findRunAndOutcomeReturnEmptyOnStableRunNotFound() throws Exception {
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "run/get", "run/outcome" -> FakeCodingHost.Reply.error("RUN_NOT_FOUND");
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                assertThat(client.findRun(RUN)).isEmpty();
                assertThat(client.findOutcome(RUN)).isEmpty();
            }
        }
    }

    private static Map<String, Object> minimalView() {
        return map(
                "session",
                summary(),
                "history",
                map("sessionId", SESSION.value(), "items", List.of(), "earlierHistoryAvailable", false),
                "activeRun",
                run(),
                "pendingInteraction",
                null,
                "eventCursor",
                cursor(RUN.value(), 3),
                "outputCursor",
                0,
                "configurationDigest",
                "sha256:config",
                "productProfileRef",
                "profile-1",
                "model",
                map("model", model(), "revision", 2, "available", true),
                "activeRunTaskSummary",
                null,
                "previewAvailable",
                false);
    }

    private static void assertProductCode(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOf(ProjectProductException.class);
        assertThat(((ProjectProductException) thrown).code()).isEqualTo(code);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> capabilities(Map<String, Object> initializeResult) {
        return (Map<String, Object>) initializeResult.get("capabilities");
    }

    private static Map<String, Object> summary() {
        return map(
                "sessionId",
                SESSION.value(),
                "projectId",
                PROJECT.value(),
                "displayName",
                "Fix the build",
                "status",
                "ACTIVE",
                "activeRunId",
                RUN.value(),
                "activeRunStatus",
                "RUNNING",
                "queuedCount",
                2,
                "lastActivityAt",
                NOW,
                "revision",
                7);
    }

    private static Map<String, Object> runWithSession() {
        Map<String, Object> value = run();
        value.put("sessionId", SESSION.value());
        return value;
    }

    private static Map<String, Object> run() {
        return map(
                "runId",
                RUN.value(),
                "status",
                "COMPLETED",
                "version",
                4,
                "updatedAt",
                NOW,
                "output",
                "final answer",
                "errorCode",
                null,
                "errorMessage",
                null,
                "diagnosticId",
                null,
                "terminationReason",
                null,
                "usage",
                map(
                        "inputTokens",
                        11,
                        "outputTokens",
                        12,
                        "cachedInputTokens",
                        3,
                        "modelCalls",
                        2,
                        "toolCalls",
                        5,
                        "childRuns",
                        0,
                        "costMinorUnits",
                        8,
                        "wallTimeMillis",
                        999),
                "result",
                map(
                        "outcome",
                        "SUCCESS",
                        "summary",
                        "done",
                        "outputSchemaId",
                        "schema-1",
                        "outputSchemaVersion",
                        "1",
                        "structuredOutput",
                        map("ok", true),
                        "artifacts",
                        List.of(map(
                                "artifactId", "artifact-1", "artifactType", "text", "version", "1", "title", "Report")),
                        "warnings",
                        List.of("safe warning")));
    }

    private static Map<String, Object> model() {
        return map(
                "id",
                "model-1",
                "displayName",
                "Model One",
                "providerId",
                "provider-1",
                "providerDisplayName",
                "Provider One",
                "capabilities",
                List.of("TEXT_CHAT", "TOOL_CALLING"),
                "contextWindow",
                100_000,
                "maxOutputTokens",
                8_000,
                "state",
                map(
                        "connection",
                        "CONNECTED",
                        "bindingAvailability",
                        "AVAILABLE",
                        "runtime",
                        "NORMAL",
                        "runScope",
                        "ACTIVE_RUN"),
                "unavailableReason",
                "",
                "controls",
                map(
                        "responseMode",
                        map(
                                "kind",
                                "responseMode",
                                "visible",
                                true,
                                "readOnly",
                                false,
                                "allowedValues",
                                List.of("FAST", "RECOMMENDED", "DEEP"),
                                "recommendedValue",
                                "RECOMMENDED",
                                "effectiveSummary",
                                "Balanced"),
                        "reasoningEffort",
                        map(
                                "kind",
                                "reasoningEffort",
                                "visible",
                                false,
                                "readOnly",
                                false,
                                "allowedValues",
                                List.of(),
                                "recommendedValue",
                                "MEDIUM",
                                "effectiveSummary",
                                "Standard")),
                "recommendedPreferences",
                map("responseMode", "RECOMMENDED", "effort", null),
                "imageInput",
                false,
                "imageInputProfile",
                null);
    }

    private static Map<String, Object> interaction() {
        return map(
                "interactionId",
                "interaction-1",
                "runId",
                RUN.value(),
                "sessionId",
                SESSION.value(),
                "revision",
                4,
                "kind",
                "approval",
                "state",
                "PENDING",
                "title",
                "Approve command",
                "safePrompt",
                "Run a command",
                "allowedActions",
                List.of("approve", "reject"),
                "inputContract",
                map(
                        "type",
                        "none",
                        "maximumCharacters",
                        0,
                        "minimumSelections",
                        0,
                        "maximumSelections",
                        0,
                        "maximumParts",
                        0,
                        "maximumBytes",
                        0,
                        "options",
                        List.of(),
                        "schemaRef",
                        null),
                "target",
                map(
                        "type",
                        "command",
                        "reference",
                        "command-1",
                        "version",
                        "1",
                        "digest",
                        "sha256:target",
                        "safeSummary",
                        "Run tests"),
                "requester",
                map("principalType", "local-user", "displayLabel", "Operator"),
                "createdAt",
                NOW,
                "expiresAt",
                NOW + 60_000,
                "consequences",
                map("accepted", "Executes", "rejected", "Skips", "expired", "Expires"),
                "approvalPresentation",
                map(
                        "title",
                        "Approve command",
                        "purpose",
                        "Run tests",
                        "contentType",
                        "command",
                        "content",
                        "npm test",
                        "environment",
                        List.of(map("label", "Shell", "value", "powershell")),
                        "technical",
                        List.of(),
                        "risk",
                        "LOW"),
                "clarificationPresentation",
                null);
    }

    private static Map<String, Object> cursor(String runId, long sequence) {
        return map("runId", runId, "feedVersion", "1", "sequence", sequence);
    }

    private static Map<String, Object> event() {
        return eventWithSession(SESSION.value());
    }

    private static Map<String, Object> eventWithSession(String sessionId) {
        return map(
                "eventId",
                "event-1",
                "eventType",
                "assistant.message-committed",
                "eventSchemaVersion",
                "1",
                "runId",
                RUN.value(),
                "sessionId",
                sessionId,
                "sequence",
                1,
                "cursor",
                cursor(RUN.value(), 1),
                "occurredAt",
                NOW,
                "correlationId",
                null,
                "causationId",
                null,
                "payload",
                map("kind", "message-committed", "messageId", "message-1", "messageSequence", 1),
                "payloadUnsupported",
                false);
    }

    private static Map<String, Object> output(String textDelta) {
        return map(
                "runId",
                RUN.value(),
                "sessionId",
                SESSION.value(),
                "modelCallId",
                "call-1",
                "generationId",
                "generation-1",
                "physicalAttempt",
                1,
                "sequence",
                1,
                "type",
                "ASSISTANT_TEXT_DELTA",
                "textDelta",
                textDelta,
                "occurredAt",
                NOW);
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return values;
    }
}
