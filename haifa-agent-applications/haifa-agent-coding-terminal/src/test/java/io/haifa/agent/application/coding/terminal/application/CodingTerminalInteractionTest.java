package io.haifa.agent.application.coding.terminal.application;

import static io.haifa.agent.application.coding.terminal.application.CodingTerminalTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.state.ClarificationDetails;
import io.haifa.agent.application.coding.terminal.state.TerminalSelectorDetail;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.state.TranscriptItem;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingWorkspaceView;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodingTerminalInteractionTest {
    @Test
    void approvalReopenUsesControlEffectsAndDoesNotResurrectAResolvedInteraction() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        FakeClient client = new FakeClient(view(Optional.of(approval()))) {
            @Override
            public Optional<InteractionView> pendingInteraction(io.haifa.agent.core.run.AgentRunId runId) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return Optional.of(approval());
            }
        };
        var controls = new ArrayDeque<Runnable>();
        var interactive = new ArrayDeque<Runnable>();
        var maintenance = new ArrayDeque<Runnable>();
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(PROJECT_ID, client, pump, new TerminalUiReducer(),
                TerminalUiState.initial(120, 40), controls::add, interactive::add, maintenance::add);
        controller.open(SESSION_ID);
        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));
        controller.accept(input(TerminalInput.Kind.EXECUTE_COMMAND, "/approval"));
        assertThat(interactive).isEmpty();
        var worker = new Thread(controls.removeFirst());
        worker.start();
        try {
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, "draft", 5));
            pump.offer(new TerminalUiAction.TerminalResized(90, 25));
            pump.offer(new TerminalUiAction.RunEventReceived(event(1,
                    new RunEventPayloads.InteractionLifecycle("interaction-1", "APPROVAL", "APPLIED", "safe"))));
            controller.drainEvents();
            assertThat(controller.state().editorBuffer()).isEqualTo("draft");
            assertThat(controller.state().columns()).isEqualTo(90);
        } finally {
            release.countDown();
            worker.join(5_000);
        }
        controller.drainEvents();
        assertThat(controller.state().selector()).isEmpty();
        assertThat(client.reconcileCalls).isZero();
    }

    @Test
    void dismissedPendingApprovalCanBeReopenedWithoutLosingTheDraft() {
        for (var close : List.of(TerminalInput.Kind.CANCEL_OR_CLOSE, TerminalInput.Kind.INTERRUPT)) {
            FakeClient client = new FakeClient(view(Optional.of(approval())));
            var controller = controller(client);
            controller.open(SESSION_ID);
            controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, "draft", 5));
            controller.accept(input(close, ""));
            assertThat(controller.state().selector()).isEmpty();
            controller.accept(input(TerminalInput.Kind.EXECUTE_COMMAND, "/approval"));
            assertThat(controller.state().selector()).hasValueSatisfying(
                    selector -> assertThat(selector.options()).containsExactly("Reject", "Allow once"));
            assertThat(controller.state().editorBuffer()).isEqualTo("draft");
            assertThat(client.respondedActions).isEmpty();
        }
    }

    @Test
    void resettingSessionApprovalRunsInTheBackgroundAndKeepsTheComposerUsable() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var effects = new ArrayDeque<Runnable>();
        FakeClient client = new FakeClient(view(Optional.empty())) {
            @Override
            public void resetSessionApproval(io.haifa.agent.core.session.AgentSessionId sessionId) {
                assertThat(sessionId).isEqualTo(SESSION_ID);
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        };
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID, client, pump, new TerminalUiReducer(), TerminalUiState.initial(120, 40), effects::add);
        controller.open(SESSION_ID);
        controller.accept(input(TerminalInput.Kind.SUBMIT, "/approval reset"));
        var worker = new Thread(effects.remove());
        worker.start();
        try {
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(controller.state().status()).isEqualTo("Resetting session approval");
            controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, "next task", 9));
            pump.offer(new TerminalUiAction.TerminalResized(90, 25));
            controller.drainEvents();
            controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));
            assertThat(controller.state().columns()).isEqualTo(90);
            assertThat(controller.state().editorBuffer()).isEqualTo("next task");
        } finally {
            release.countDown();
            worker.join(5_000);
        }
        controller.drainEvents();
        assertThat(controller.state().status()).isEqualTo("Session approval reset · ask each time");
    }

    @Test
    void sessionApprovalUsesTheProductOptionAndKeepsTheUiResponsiveUntilItReturns() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var effects = new ArrayDeque<Runnable>();
        FakeClient client = new FakeClient(view(Optional.of(approval()))) {
            @Override
            public Optional<io.haifa.agent.application.project.product.coding.client.CodingApprovalPrompt>
                    prepareApproval(InteractionView interaction) {
                return Optional.of(new io.haifa.agent.application.project.product.coding.client.CodingApprovalPrompt(
                        interaction, true));
            }

            @Override
            public io.haifa.agent.runtime.api.InteractionResponseReceipt allowForSession(
                    InteractionView interaction, String key) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return respond(interaction, InteractionAction.APPROVE, key);
            }
        };
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID, client, pump, new TerminalUiReducer(), TerminalUiState.initial(120, 40), effects::add);
        controller.open(SESSION_ID);
        assertThat(controller.state().selector().orElseThrow().options())
                .containsExactly("Reject", "Allow once", "Allow for this session");
        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, "保留草稿", 4));
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        var worker = new Thread(effects.remove());
        worker.start();
        try {
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(controller.state().selector()).isEmpty();
            assertThat(controller.state().status()).isEqualTo("Approving");
            pump.offer(new TerminalUiAction.TerminalResized(90, 25));
            controller.drainEvents();
            controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));
            assertThat(controller.state().columns()).isEqualTo(90);
            assertThat(controller.state().editorBuffer()).isEqualTo("保留草稿");
        } finally {
            release.countDown();
            worker.join(5_000);
        }
        controller.drainEvents();
        assertThat(client.respondedActions).containsExactly(InteractionAction.APPROVE);
    }

    @Test
    void togglesAMultiChoiceClarificationOptionOnAndOff() {
        FakeClient client = new FakeClient(view(Optional.of(clarification(true))));
        client.reconciledView = view(Optional.empty());
        var controller = controller(client);
        controller.open(SESSION_ID);

        assertThat(controller.state().selector().orElseThrow().options())
                .containsExactly("[ ] Postgres", "[ ] SQLite", "Type your own answer", "Send answer", "Cancel");

        controller.accept(input(TerminalInput.Kind.TOGGLE_SELECTION, ""));
        assertThat(controller.state().selector().orElseThrow().options().get(0)).isEqualTo("[\u2713] Postgres");

        controller.accept(input(TerminalInput.Kind.TOGGLE_SELECTION, ""));
        assertThat(controller.state().selector().orElseThrow().options().get(0)).isEqualTo("[ ] Postgres");
    }

    @Test
    void submitsFreeTextAsTheClarificationAnswerInsteadOfASteer() {
        FakeClient client = new FakeClient(view(Optional.of(clarification(false))));
        client.reconciledView = view(Optional.empty());
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(controller.state().selector()).isEmpty();

        controller.accept(input(TerminalInput.Kind.SUBMIT, "first line\nsecond line"));

        assertThat(client.steeredMessages).isEmpty();
        assertThat(client.respondedInputs).hasSize(1);
        String answer = ((io.haifa.agent.core.content.TextPart)
                        client.respondedInputs.get(0).get(0))
                .text();
        assertThat(answer).isEqualTo("selected=\ncustom=first line second line");
    }

    @Test
    void submitsFreeTextAsAFollowUpInsteadOfASteer() {
        FakeClient client = new FakeClient(view(Optional.of(clarification(false))));
        client.reconciledView = view(Optional.empty());
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(controller.state().selector()).isEmpty();

        controller.accept(input(TerminalInput.Kind.FOLLOW_UP, "sqlite"));

        assertThat(client.steeredMessages).isEmpty();
        assertThat(client.respondedInputs).hasSize(1);
        assertThat(((io.haifa.agent.core.content.TextPart)
                                client.respondedInputs.get(0).get(0))
                        .text())
                .isEqualTo("selected=\ncustom=sqlite");
    }

    @Test
    void togglesASingleChoiceClarificationOptionOnAndOff() {
        FakeClient client = new FakeClient(view(Optional.of(clarification(false))));
        client.reconciledView = view(Optional.empty());
        var controller = controller(client);
        controller.open(SESSION_ID);

        assertThat(controller.state().selector().orElseThrow().options())
                .containsExactly("( ) Postgres", "( ) SQLite", "Type your own answer", "Cancel");

        controller.accept(input(TerminalInput.Kind.TOGGLE_SELECTION, ""));
        assertThat(controller.state().selector().orElseThrow().options().get(0)).isEqualTo("(\u2713) Postgres");

        controller.accept(input(TerminalInput.Kind.TOGGLE_SELECTION, ""));
        assertThat(controller.state().selector().orElseThrow().options().get(0)).isEqualTo("( ) Postgres");
    }

    @Test
    void walksAMultiQuestionClarificationWithItsStepAndOptionRationales() {
        FakeClient client = new FakeClient(view(Optional.of(multiQuestionClarification())));
        client.reconciledView = view(Optional.empty());
        var controller = controller(client);
        controller.open(SESSION_ID);

        var first = controller.state().selector().orElseThrow();
        assertThat(first.title()).isEqualTo("Clarification · Step 1 of 2 · Storage");
        assertThat(first.options()).containsExactly("( ) Postgres", "( ) SQLite", "Type your own answer", "Cancel");
        assertThat(first.details())
                .extracting(TerminalSelectorDetail::description)
                .containsExactly(
                        "Runs as a server",
                        "",
                        ClarificationDetails.CUSTOM_ENTRY_DESCRIPTION,
                        ClarificationDetails.CANCEL_DESCRIPTION);
        assertThat(controller.state().transcript()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(TranscriptItem.Kind.CLARIFICATION);
            assertThat(item.body()).isEqualTo("Storage: Which storage?\nLocation: Where should it live?");
        });

        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(controller.state().selector().orElseThrow().title())
                .isEqualTo("Clarification · Step 2 of 2 · Location");
        assertThat(controller.state().status()).isEqualTo("Awaiting your clarification answer");
    }

    @Test
    void pendingInteractionUsesTheSameSelectorInputOwnerAndRuntimeClient() {
        InteractionView interaction = approval();
        FakeClient client = new FakeClient(view(Optional.of(interaction)));
        client.reconciledView = view(Optional.empty());
        var controller = controller(client);
        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, "preserved draft", 9));
        controller.open(SESSION_ID);

        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().selector().orElseThrow().options()).containsExactly("Reject", "Allow once");
        assertThat(controller.state().editorBuffer()).isEqualTo("preserved draft");
        assertThat(controller.state().editorCursor()).isEqualTo(9);
        assertThat(controller.state().transcript()).singleElement().satisfies(item -> {
            assertThat(item.body()).isEqualTo("Allow file change?").doesNotContain("UNSAFE_FREE_TEXT");
            assertThat(item.approvalDetails()).hasValueSatisfying(details -> {
                assertThat(details.title()).isEqualTo("Approval");
                assertThat(details.purpose()).isEqualTo("Run tool");
                assertThat(details.content()).isEqualTo("Allow file change?");
                assertThat(details.allowedActions()).containsExactly("reject", "approve");
            });
        });
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(client.respondedActions).containsExactly(InteractionAction.APPROVE);
        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().editorBuffer()).isEqualTo("preserved draft");
        assertThat(controller.state().editorCursor()).isEqualTo(9);
        assertThat(controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.status())
                .isEqualTo("RESPONDED"));
    }

    @Test
    void ctrlOExpandsThePendingApprovalWithoutResponding() {
        InteractionView interaction = approval();
        FakeClient client = new FakeClient(view(Optional.of(interaction)));
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.TOGGLE_EXPANSION, ""));

        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.expanded())
                .isTrue());
        assertThat(client.respondedActions).isEmpty();
    }

    @Test
    void sessionNotFoundWhenRespondingDoesNotReopenTheStaleApproval() {
        FakeClient client = new FakeClient(view(Optional.of(approval())));
        client.responseFailure = new ProjectProductException("SESSION_NOT_FOUND", "Session unavailable");
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(client.respondedActions).containsExactly(InteractionAction.APPROVE);
        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().recoverableError()).contains("SESSION_NOT_FOUND");

        client.summaries = List.of(view(Optional.empty()).summary());
        controller.accept(input(TerminalInput.Kind.SUBMIT, "/resume"));

        assertThat(controller.state().selector())
                .hasValueSatisfying(selector -> assertThat(selector.kind()).isEqualTo("resume"));
    }

    @Test
    void approvalImmediatelyUpdatesUiWhileRuntimeResponseRemainsInBackground() {
        InteractionView interaction = approval();
        FakeClient client = new FakeClient(view(Optional.of(interaction)));
        var queuedEffects = new ArrayDeque<Runnable>();
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                queuedEffects::add);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().status()).isEqualTo("Approving");
        assertThat(client.respondedActions).isEmpty();
        assertThat(queuedEffects).hasSize(1);

        queuedEffects.remove().run();
        controller.drainEvents();

        assertThat(client.respondedActions).containsExactly(InteractionAction.APPROVE);
    }

    @Test
    void cursorMaintenanceCannotQueueAheadOfAnApprovalResponse() {
        InteractionView interaction = approval();
        FakeClient client = new FakeClient(view(Optional.of(interaction)));
        var controlEffects = new ArrayDeque<Runnable>();
        var interactiveEffects = new ArrayDeque<Runnable>();
        var maintenanceEffects = new ArrayDeque<Runnable>();
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                controlEffects::add,
                interactiveEffects::add,
                maintenanceEffects::add);
        controller.open(SESSION_ID);
        pump.offer(new TerminalUiAction.RunEventReceived(
                event(1, new RunEventPayloads.AssistantTextDelta("generation-1", "delta"))));
        controller.drainEvents();

        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(maintenanceEffects).hasSize(1);
        assertThat(interactiveEffects).isEmpty();
        assertThat(controlEffects).hasSize(1);
        controlEffects.remove().run();
        controller.drainEvents();

        assertThat(client.respondedActions).containsExactly(InteractionAction.REJECT);
        assertThat(maintenanceEffects).hasSize(1);
    }

    @Test
    void pendingInteractionEventHydratesApprovalSelectorInBackground() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.reconciledView = view(Optional.of(approval()));
        var controlEffects = new ArrayDeque<Runnable>();
        var interactiveEffects = new ArrayDeque<Runnable>();
        var maintenanceEffects = new ArrayDeque<Runnable>();
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                controlEffects::add,
                interactiveEffects::add,
                maintenanceEffects::add);
        controller.open(SESSION_ID);
        pump.offer(new TerminalUiAction.RunEventReceived(event(
                1,
                new RunEventPayloads.InteractionLifecycle(
                        "interaction-1", "APPROVAL", "PENDING", "UNSAFE_FREE_TEXT"))));

        controller.drainEvents();

        assertThat(client.reconcileCalls).isZero();
        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .contains("Structured approval details are loading."));
        assertThat(controlEffects).hasSize(1);
        assertThat(interactiveEffects).isEmpty();
        assertThat(maintenanceEffects).hasSize(1);

        controlEffects.removeFirst().run();
        controller.drainEvents();

        assertThat(client.pendingInteractionCalls).isEqualTo(1);
        assertThat(client.reconcileCalls).isZero();
        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().transcript()).singleElement().satisfies(item -> {
            assertThat(item.body()).isEqualTo("Allow file change?");
            assertThat(item.approvalDetails()).hasValueSatisfying(details -> assertThat(details.allowedActions())
                    .containsExactly("reject", "approve"));
        });

        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(controlEffects).hasSize(1);
        controlEffects.removeFirst().run();
        controller.drainEvents();

        assertThat(client.respondedActions).containsExactly(InteractionAction.REJECT);
        assertThat(client.reconcileCalls).isZero();
    }

    @Test
    void workspaceCompletionDoesNotBlockEditorOrResizeWhileDiscoveryRuns() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.logicalPaths = List.of("src/main/App.java");
        var queuedEffects = new ArrayDeque<Runnable>();
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                queuedEffects::add);

        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, "@src", 4));
        controller.accept(new TerminalInput(TerminalInput.Kind.COMPLETION_REQUESTED, "@src", 4));
        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, "@srcx", 5));
        controller.accept(new TerminalInput(TerminalInput.Kind.TICK, "", 0));

        assertThat(controller.state().editorBuffer()).isEqualTo("@srcx");
        assertThat(controller.state().selector()).isEmpty();
        assertThat(queuedEffects).hasSize(1);

        queuedEffects.remove().run();
        controller.drainEvents();

        assertThat(controller.state().editorBuffer()).isEqualTo("@srcx");
        assertThat(controller.state().selector()).isEmpty();
    }

    @Test
    void settingsStaysUnavailableWhileTrustListsAndRevokesCurrentWorkspaceAccess() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.workspaces = List.of(
                new CodingWorkspaceView("workspace-initial", "haifa-agent", "DEVELOP", "active", false),
                new CodingWorkspaceView("workspace-docs", "haifa-agent-docs", "READ", "active", true));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/settings"));
        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().recoverableError()).contains("CAPABILITY_NOT_IMPLEMENTED");

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/trust"));
        assertThat(controller.state().selector()).hasValueSatisfying(selector -> {
            assertThat(selector.kind()).isEqualTo("workspace-trust");
            assertThat(selector.title()).isEqualTo("Workspace trust");
            assertThat(selector.options())
                    .containsExactly(
                            "haifa-agent · DEVELOP · active · workspace-initial",
                            "haifa-agent-docs · READ · active · workspace-docs · revocable");
        });

        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, "/trust revoke workspace-docs"));

        assertThat(client.revokedWorkspaces).containsExactly("workspace-docs");
        assertThat(controller.state().status()).isEqualTo("Workspace access revoked");
    }

    @Test
    void trustRevocationImmediatelyUpdatesUiWhileTheProductCallRunsInTheBackground() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        var queuedEffects = new ArrayDeque<Runnable>();
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                queuedEffects::add);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/trust revoke workspace-docs"));

        assertThat(controller.state().status()).isEqualTo("Revoking workspace access");
        assertThat(client.revokedWorkspaces).isEmpty();
        assertThat(queuedEffects).hasSize(1);

        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, "preserved draft", 15));
        assertThat(controller.state().editorBuffer()).isEqualTo("preserved draft");

        queuedEffects.remove().run();
        controller.drainEvents();

        assertThat(client.revokedWorkspaces).containsExactly("workspace-docs");
        assertThat(controller.state().status()).isEqualTo("Workspace access revoked");
    }

    @Test
    void modelCanBeSelectedBeforeTheFirstSessionAndIsAppliedAtCreation() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.models = List.of(
                model("default-model", "Default", "deepseek", "DeepSeek"),
                model("codex-model", "Codex", "openai-codex", "ChatGPT Codex"));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/model"));

        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model-provider");
        assertThat(controller.state().selector().orElseThrow().options())
                .containsExactly("DeepSeek · 1 model", "ChatGPT Codex · 1 model");
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model");
        assertThat(controller.state().selector().orElseThrow().title()).isEqualTo("Models from ChatGPT Codex");
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model-detail");
        assertThat(controller.state().selector().orElseThrow().title()).contains("Codex", "128K context");
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, "first message"));

        assertThat(client.createOptions.initialModelId()).contains("codex-model");
        assertThat(controller.state().session()).isPresent();
    }

    @Test
    void providerModelListKeepsThePreferredModelAndDetailBackPosition() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.models = List.of(
                model("deepseek-fast", "DeepSeek Fast", "deepseek", "DeepSeek"),
                model("deepseek-deep", "DeepSeek Deep", "deepseek", "DeepSeek"),
                model("codex-model", "Codex", "openai-codex", "ChatGPT Codex"));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/model deepseek-deep"));
        controller.accept(input(TerminalInput.Kind.SUBMIT, "/model"));

        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model-provider");
        assertThat(controller.state().selector().orElseThrow().selected()).isZero();
        assertThat(controller.state().selector().orElseThrow().options())
                .containsExactly("DeepSeek · 2 models", "ChatGPT Codex · 1 model");

        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model");
        assertThat(controller.state().selector().orElseThrow().selected()).isEqualTo(1);

        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model-detail");
        assertThat(controller.state().selector().orElseThrow().title()).contains("DeepSeek Deep");

        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model");
        assertThat(controller.state().selector().orElseThrow().selected()).isEqualTo(1);
    }

    @Test
    void providerModelListReturnsToProviderListOnNavigateBackAndPreservesProviderSelection() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.models = List.of(
                model("deepseek-fast", "DeepSeek Fast", "deepseek", "DeepSeek"),
                model("codex-model", "Codex", "openai-codex", "ChatGPT Codex"));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/model"));

        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model-provider");
        assertThat(controller.state().selector().orElseThrow().selected()).isZero();

        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model");
        assertThat(controller.state().selector().orElseThrow().title()).isEqualTo("Models from ChatGPT Codex");

        controller.accept(input(TerminalInput.Kind.NAVIGATE_BACK, ""));

        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model-provider");
        assertThat(controller.state().selector().orElseThrow().selected()).isEqualTo(1);
    }

    @Test
    void modelDetailReturnsToModelListOnNavigateBack() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.models = List.of(
                model("deepseek-fast", "DeepSeek Fast", "deepseek", "DeepSeek"),
                model("deepseek-deep", "DeepSeek Deep", "deepseek", "DeepSeek"),
                model("codex-model", "Codex", "openai-codex", "ChatGPT Codex"));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/model"));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model-detail");

        controller.accept(input(TerminalInput.Kind.NAVIGATE_BACK, ""));
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model");
        assertThat(controller.state().selector().orElseThrow().selected()).isZero();

        controller.accept(input(TerminalInput.Kind.NAVIGATE_BACK, ""));
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model-provider");
        assertThat(controller.state().selector().orElseThrow().selected()).isZero();
    }

    @Test
    void emptyAndUnavailableModelCatalogsRemainExplicit() {
        FakeClient emptyClient = new FakeClient(view(Optional.empty()));
        var emptyController = controller(emptyClient);

        emptyController.accept(input(TerminalInput.Kind.SUBMIT, "/model"));
        assertThat(emptyController.state().recoverableError()).contains("MODEL_LIST_EMPTY");

        FakeClient unavailableClient = new FakeClient(view(Optional.empty()));
        unavailableClient.models = List.of(unavailableModel("unavailable-model", "Unavailable"));
        var unavailableController = controller(unavailableClient);

        unavailableController.accept(input(TerminalInput.Kind.SUBMIT, "/model"));
        unavailableController.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(unavailableController.state().selector().orElseThrow().kind())
                .isEqualTo("model-detail");
        assertThat(unavailableController
                        .state()
                        .selector()
                        .orElseThrow()
                        .options()
                        .getFirst())
                .startsWith("Unavailable:");
    }

    @Test
    void directModelSelectionRejectsAnUnavailableBindingBeforeTheFirstSession() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.models = List.of(unavailableModel("unavailable-model", "Unavailable"));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/model unavailable-model"));

        assertThat(controller.state().recoverableError()).contains("MODEL_UNAVAILABLE");
        controller.accept(input(TerminalInput.Kind.SUBMIT, "first message"));
        assertThat(client.createOptions.initialModelId()).isEmpty();
    }

    @Test
    void modelDetailsExposeAReadOnlyVerifiedProfileBeforeConfirmation() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.models = List.of(model("codex-model", "Codex"));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/model"));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model-settings");
        assertThat(controller.state().selector().orElseThrow().options())
                .contains(
                        "Response mode: RECOMMENDED · Balanced",
                        "Reasoning effort: Profile default · Choose from verified reasoning effort levels");
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(controller.state().status())
                .isEqualTo("Response settings are defined by the verified model profile");
    }

    @Test
    void tabOpensVisibleCommandCandidatesAndInsertsTheSelection() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        var controller = controller(client);

        controller.accept(new TerminalInput(TerminalInput.Kind.COMPLETION_REQUESTED, "/", 1));

        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().selector().orElseThrow().options())
                .containsExactlyElementsOf(TerminalCompletionProvider.COMMANDS);
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().editorBuffer()).isEqualTo("/logout");
        assertThat(controller.state().editorCursor()).isEqualTo("/logout".length());
    }

    @Test
    void tabCompletesAWorkspaceFileInPlaceAndPreservesTheRestOfTheMessage() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.logicalPaths = List.of("README.md", "src/main/App.java", "src/test/AppTest.java");
        var controller = controller(client);
        String message = "inspect @src/ma after";
        int cursor = message.indexOf(" after");

        controller.accept(new TerminalInput(TerminalInput.Kind.COMPLETION_REQUESTED, message, cursor));

        assertThat(controller.state().selector().orElseThrow().options()).containsExactly("@src/main/App.java");
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(controller.state().editorBuffer()).isEqualTo("inspect @src/main/App.java after");
        assertThat(controller.state().editorCursor()).isEqualTo("inspect @src/main/App.java".length());
    }

    @Test
    void commandAliasOpensTheSameVisibleCommandPalette() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/command"));

        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("command-palette");
        assertThat(controller.state().selector().orElseThrow().title()).isEqualTo("Commands");
    }

    @Test
    void commandPaletteInputOpensTheCommandsSelector() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.COMMAND_PALETTE, ""));

        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().selector().orElseThrow().title()).isEqualTo("Commands");
    }

    @Test
    void cycleModelNextAndPreviousSwitchesModelSelection() {
        CodingModelOption model1 = model("deepseek-chat", "DeepSeek Chat");
        CodingModelOption model2 = model("claude-sonnet", "Claude Sonnet");
        FakeClient client = new FakeClient(view(Optional.empty(), 0, "session", model1));
        client.models = List.of(model1, model2);
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.CYCLE_MODEL_NEXT, ""));
        assertThat(client.selectedModelId).isEqualTo("claude-sonnet");

        controller.accept(input(TerminalInput.Kind.CYCLE_MODEL_PREVIOUS, ""));
        assertThat(client.selectedModelId).isEqualTo("deepseek-chat");
    }

    @Test
    void asyncModelLoadingDoesNotCloseModalSelectorOpenedWhileLoading() {
        CodingModelOption model1 = model("deepseek-chat", "DeepSeek Chat");
        CodingModelOption model2 = model("claude-sonnet", "Claude Sonnet");
        FakeClient client = new FakeClient(view(Optional.empty(), 0, "session", model1));
        client.models = List.of(model1, model2);
        var queuedEffects = new ArrayDeque<Runnable>();
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                queuedEffects::add);
        controller.open(SESSION_ID);

        // User triggers model cycle, which schedules async model loading
        controller.accept(input(TerminalInput.Kind.CYCLE_MODEL_NEXT, ""));
        assertThat(controller.state().status()).isEqualTo("Loading models");
        assertThat(queuedEffects).hasSize(1);

        // While loading, user edits draft and opens command palette
        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, "user draft", 10));
        controller.accept(input(TerminalInput.Kind.COMMAND_PALETTE, ""));
        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("command-palette");
        assertThat(controller.preservedDraft()).contains("user draft");

        // Background effect completes
        queuedEffects.remove().run();
        controller.drainEvents();

        // Modal selector must remain open, model must not be changed, draft preserved
        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("command-palette");
        assertThat(client.selectedModelId).isNull();
        assertThat(controller.preservedDraft()).contains("user draft");

        // Cancel modal restores draft cleanly
        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));
        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().editorBuffer()).isEqualTo("user draft");
    }

    @Test
    void newSessionConsecutiveModelCyclesAdvanceThroughAvailableModels() {
        CodingModelOption modelA = model("model-a", "Model A");
        CodingModelOption modelB = model("model-b", "Model B");
        CodingModelOption modelC = model("model-c", "Model C");
        FakeClient client = new FakeClient(view(Optional.empty(), 0, "session", modelA));
        client.models = List.of(modelA, modelB, modelC);
        var controller = controller(client);
        controller.open(SESSION_ID);

        // User enters /new
        controller.accept(input(TerminalInput.Kind.SUBMIT, "/new"));
        assertThat(controller.state().status()).isEqualTo("New session: enter the first message");

        // First cycle in /new selects Model B
        controller.accept(input(TerminalInput.Kind.CYCLE_MODEL_NEXT, ""));
        assertThat(controller.state().status()).isEqualTo("Model selected for next session");

        // Second cycle in /new must select Model C (not repeat Model B!)
        controller.accept(input(TerminalInput.Kind.CYCLE_MODEL_NEXT, ""));
        assertThat(controller.state().status()).isEqualTo("Model selected for next session");

        // First turn submission creates session with Model C
        controller.accept(input(TerminalInput.Kind.SUBMIT, "first turn"));
        assertThat(client.createOptions.initialModelId()).contains("model-c");
    }

    @Test
    void themeCommandSwitchesThemeDirectlyOrViaSelector() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        var controller = controller(client);
        controller.open(SESSION_ID);

        assertThat(controller.state().themeName()).isEqualTo("dark");

        // Direct theme switch
        controller.accept(input(TerminalInput.Kind.SUBMIT, "/theme nord"));
        assertThat(controller.state().themeName()).isEqualTo("nord");
        assertThat(controller.state().status()).isEqualTo("Theme set to nord");

        // Invalid theme does not change theme and reports available options
        controller.accept(input(TerminalInput.Kind.SUBMIT, "/theme unknown_palette"));
        assertThat(controller.state().themeName()).isEqualTo("nord");
        assertThat(controller.state().status()).contains("Unknown theme", "dark, light, high-contrast, nord");

        // /theme with no arguments opens selector
        controller.accept(input(TerminalInput.Kind.SUBMIT, "/theme"));
        assertThat(controller.state().selector()).hasValueSatisfying(selector -> {
            assertThat(selector.kind()).isEqualTo("theme-selector");
            assertThat(selector.options()).containsExactly("dark", "light", "high-contrast", "nord");
            assertThat(selector.selected()).isEqualTo(3); // "nord" is at index 3
        });

        // Select "light" (move up twice from 3 to 1) and confirm
        controller.accept(input(TerminalInput.Kind.SELECT_PREVIOUS, ""));
        controller.accept(input(TerminalInput.Kind.SELECT_PREVIOUS, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().themeName()).isEqualTo("light");
        assertThat(controller.state().status()).isEqualTo("Theme set to light");
    }
}
