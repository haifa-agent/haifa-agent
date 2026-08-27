package io.haifa.agent.application.coding.terminal.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingRestoredMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionCreateOptions;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryItem;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.CodingShellPlan;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProgressView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.application.project.product.coding.client.CodingBrowserLoginView;
import io.haifa.agent.application.project.product.coding.client.CodingDeviceLoginView;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunEventListener;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionConsequenceView;
import io.haifa.agent.runtime.api.InteractionInputContract;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionRequesterView;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionResponseReceiptStatus;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionTargetView;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodingTerminalControllerTest {
    private static final ProjectId PROJECT_ID = new ProjectId("project-1");
    private static final AgentSessionId SESSION_ID = new AgentSessionId("session-1");

    @Test
    void firstStartupOpensConnectionOnboardingWhenTheSelectedCredentialIsMissing() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        CodingAuthenticationClient authentication = new CodingAuthenticationClient() {
            @Override
            public boolean connectionRequired() {
                return true;
            }

            @Override
            public List<CodingAuthenticationView> connections() {
                return List.of();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean logout(String connectionId) {
                return false;
            }
        };
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                authentication,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run);

        controller.start(CodingTerminalStartup.empty());

        assertThat(controller.state().selector()).get().satisfies(selector -> {
            assertThat(selector.kind()).isEqualTo("auth-login");
            assertThat(selector.title()).isEqualTo("Connect a model to get started");
            assertThat(selector.options()).containsExactly("ChatGPT subscription", "Provider API key (secure input)");
        });
    }

    @Test
    void enabledAntigravityLoginAppearsInOnboardingAndStartsItsBrowserFlow() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        CodingAuthenticationClient authentication = new CodingAuthenticationClient() {
            @Override
            public boolean connectionRequired() {
                return true;
            }

            @Override
            public boolean antigravityConnectionSupported() {
                return true;
            }

            @Override
            public List<CodingAuthenticationView> connections() {
                return List.of();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodingAuthenticationView loginAntigravityBrowser(
                    java.util.function.Consumer<CodingBrowserLoginView> instructions,
                    java.util.function.Consumer<CodingAuthenticationProgressView> progress) {
                started.set(true);
                progress.accept(
                        new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.WAITING_USER));
                instructions.accept(new CodingBrowserLoginView(
                        URI.create("https://accounts.google.com/o/oauth2/v2/auth?state=test"), 1_000));
                return new CodingAuthenticationView(
                        "model-auth://google-antigravity/default",
                        "google-antigravity",
                        CodingAuthenticationView.Method.ANTIGRAVITY_SUBSCRIPTION,
                        CodingAuthenticationView.Status.AUTHENTICATED,
                        "Google account",
                        Optional.empty(),
                        OptionalLong.empty(),
                        true);
            }

            @Override
            public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean logout(String connectionId) {
                return false;
            }
        };
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                authentication,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run);

        controller.start(CodingTerminalStartup.empty());

        assertThat(controller.state().selector().orElseThrow().options())
                .containsExactly("ChatGPT subscription", "Antigravity subscription", "Provider API key (secure input)");
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        controller.drainEvents();

        assertThat(started).isTrue();
        assertThat(controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("Antigravity connection");
            assertThat(item.status()).isEqualTo("CONNECTED");
        });
        assertThat(controller.state().status()).isEqualTo("Connected to Antigravity (UNOFFICIAL_LOCAL_COMPAT)");
    }

    @Test
    void deviceLoginPrintsTheBrowserUrlAndUserCodeAsPersistentTranscriptContent() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        CodingAuthenticationClient authentication = new CodingAuthenticationClient() {
            @Override
            public List<CodingAuthenticationView> connections() {
                return List.of();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodingAuthenticationView loginCodexDevice(
                    java.util.function.Consumer<CodingDeviceLoginView> instructions,
                    java.util.function.Consumer<CodingAuthenticationProgressView> progress) {
                progress.accept(
                        new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.WAITING_USER));
                instructions.accept(new CodingDeviceLoginView(
                        URI.create("https://auth.openai.com/codex/device"), "ABCD-1234", 1_000));
                progress.accept(
                        new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.EXCHANGING));
                progress.accept(new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.STORING));
                return authenticatedCodexConnection();
            }

            @Override
            public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean logout(String connectionId) {
                return false;
            }
        };
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                authentication,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/login codex device"));
        controller.drainEvents();

        assertThat(controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("ChatGPT device login");
            assertThat(item.body())
                    .contains("Browser URL: https://auth.openai.com/codex/device", "Device code: ABCD-1234");
            assertThat(item.status()).isEqualTo("WAITING");
        });
        assertThat(controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("ChatGPT Codex connection");
            assertThat(item.body()).contains("Credentials were saved to ~/.haifa-agent/auth.json");
            assertThat(item.status()).isEqualTo("CONNECTED");
        });
        assertThat(controller.state().status()).isEqualTo("Connected to ChatGPT Codex (UNOFFICIAL_LOCAL_COMPAT)");
    }

    @Test
    void browserLoginPrintsTheAuthorizationUrlWhenAutomaticLaunchFallsBack() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        CodingAuthenticationClient authentication = new CodingAuthenticationClient() {
            @Override
            public List<CodingAuthenticationView> connections() {
                return List.of();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser(
                    java.util.function.Consumer<CodingBrowserLoginView> instructions,
                    java.util.function.Consumer<CodingAuthenticationProgressView> progress) {
                progress.accept(
                        new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.WAITING_USER));
                instructions.accept(new CodingBrowserLoginView(
                        URI.create("https://auth.openai.com/oauth/authorize?client_id=test&state=state"), 1_000));
                progress.accept(
                        new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.EXCHANGING));
                progress.accept(new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.STORING));
                return authenticatedCodexConnection();
            }

            @Override
            public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean logout(String connectionId) {
                return false;
            }
        };
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                authentication,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/login codex browser"));
        controller.drainEvents();

        assertThat(controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("ChatGPT browser login");
            assertThat(item.body())
                    .contains(
                            "A browser sign-in was requested.",
                            "If it did not open, use this URL: "
                                    + "https://auth.openai.com/oauth/authorize?client_id=test&state=state");
        });
        assertThat(controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("ChatGPT Codex connection");
            assertThat(item.body())
                    .contains("Credentials were saved to ~/.haifa-agent/auth.json", "UNOFFICIAL_LOCAL_COMPAT");
            assertThat(item.status()).isEqualTo("CONNECTED");
        });
        assertThat(controller.state().toString()).doesNotContain("client_id=test", "state=state");
        assertThat(controller.state().status()).isEqualTo("Connected to ChatGPT Codex (UNOFFICIAL_LOCAL_COMPAT)");
    }

    @Test
    void browserLoginFailureRemainsVisibleWithTheFailedStageAndAction() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        CodingAuthenticationClient authentication = new CodingAuthenticationClient() {
            @Override
            public List<CodingAuthenticationView> connections() {
                return List.of();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser(
                    java.util.function.Consumer<CodingBrowserLoginView> instructions,
                    java.util.function.Consumer<CodingAuthenticationProgressView> progress) {
                progress.accept(
                        new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.EXCHANGING));
                throw new IllegalStateException("AUTH_REAUTH_REQUIRED");
            }

            @Override
            public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean logout(String connectionId) {
                return false;
            }
        };
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                authentication,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/login codex browser"));
        controller.drainEvents();

        assertThat(controller.state().transcript()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("ChatGPT Codex connection failed");
            assertThat(item.body())
                    .contains(
                            "Last stage: Authorization received. Exchanging it for Codex credentials.",
                            "Reason: AUTH_REAUTH_REQUIRED",
                            "verify the OAuth Client ID and redirect registration");
            assertThat(item.status()).isEqualTo("FAILED");
        });
        assertThat(controller.state().recoverableError()).contains("AUTH_REAUTH_REQUIRED");
    }

    @Test
    void resumeSelectorOpensTheSelectedRealSession() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.summaries = List.of(client.view.summary());
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/resume"));
        assertThat(controller.state().selector()).isPresent();
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(client.opened).containsExactly(SESSION_ID);
        assertThat(controller.state().session()).contains(client.view);
        assertThat(controller.state().selector()).isEmpty();
    }

    @Test
    void startupResumeSelectorUsesTheExistingBoundedSessionPicker() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.summaries = List.of(client.view.summary());
        var controller = controller(client);

        controller.start(CodingTerminalStartup.selectSession());

        assertThat(client.listLimit).isEqualTo(50);
        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().selector().orElseThrow().options().getFirst())
                .contains("session", "ACTIVE", "session-1");
    }

    @Test
    void startupLastOpensTheMostRecentSessionBeforeSubmittingThePrompt() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.summaries = List.of(client.view.summary());
        var controller = controller(client);

        controller.start(CodingTerminalStartup.lastSession(Optional.of("continue the work")));

        assertThat(client.listLimit).isEqualTo(1);
        assertThat(client.opened).containsExactly(SESSION_ID);
        assertThat(client.submittedMessages).containsExactly("continue the work");
        assertThat(controller.state().session()).isPresent();
    }

    @Test
    void startupPromptDoesNotTakeOverAnActiveRun() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);

        controller.start(CodingTerminalStartup.session(SESSION_ID, Optional.of("do not steer this")));

        assertThat(client.submittedMessages).isEmpty();
        assertThat(client.steeredMessages).isEmpty();
        assertThat(controller.state().editorBuffer()).isEqualTo("do not steer this");
        assertThat(controller.state().recoverableError()).contains("RUN_TAKEOVER_NOT_SUPPORTED");
    }

    @Test
    void openingASessionRestoresTheSafeHistoryProjection() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.history = new CodingSessionHistoryPage(
                SESSION_ID,
                List.of(
                        new CodingSessionHistoryItem(
                                "history-1",
                                CodingSessionHistoryItem.Kind.USER,
                                "You",
                                "previous question",
                                "COMPLETED",
                                1,
                                Instant.EPOCH),
                        new CodingSessionHistoryItem(
                                "history-2",
                                CodingSessionHistoryItem.Kind.ASSISTANT,
                                "Assistant",
                                "previous answer",
                                "COMPLETED",
                                2,
                                Instant.EPOCH)),
                true);
        var controller = controller(client);

        controller.open(SESSION_ID);

        assertThat(controller.state().transcript())
                .extracting(value -> value.body())
                .containsExactly(
                        "Earlier history is not loaded or has been compacted.", "previous question", "previous answer");
    }

    @Test
    void altUpRestoresTheSelectedDurableFollowUpIntoTheEditor() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        CodingQueuedMessage queued = new CodingQueuedMessage("follow-1", SESSION_ID, "queued task", 1, 2);
        client.restorable = List.of(queued);
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.RESTORE, ""));
        assertThat(controller.state().selector()).isPresent();
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(client.restored).containsExactly("follow-1");
        assertThat(controller.state().editorBuffer()).isEqualTo("queued task");
        assertThat(controller.state().editorCursor()).isEqualTo("queued task".length());
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
        assertThat(controller.state().editorBuffer()).isEqualTo("preserved draft");
        assertThat(controller.state().editorCursor()).isEqualTo(9);
        assertThat(controller.state().transcript()).singleElement().satisfies(item -> {
            assertThat(item.body())
                    .contains(
                            "Action: Approval",
                            "Target: workspace file",
                            "Risk: On approval: Run tool",
                            "Network: Not declared by runtime",
                            "Reason: Allow file change?",
                            "Allowed: reject / approve");
            assertThat(item.approvalDetails()).isPresent();
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
            assertThat(item.approvalDetails()).isPresent();
            assertThat(item.body()).contains("Allowed: reject / approve");
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
    void settingsAndTrustDoNotOpenDecorativeSelectors() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/settings"));
        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().recoverableError()).contains("CAPABILITY_NOT_IMPLEMENTED");

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/trust"));
        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().recoverableError()).contains("CAPABILITY_NOT_IMPLEMENTED");
    }

    @Test
    void modelCanBeSelectedBeforeTheFirstSessionAndIsAppliedAtCreation() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.models = List.of(
                new CodingModelOption("default-model", "Default", "provider", "Provider", Set.of(), 128_000),
                new CodingModelOption("codex-model", "Codex", "openai-codex", "ChatGPT Codex", Set.of(), 128_000));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/model"));

        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().selector().orElseThrow().title()).isEqualTo("Model for next session");
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, "first message"));

        assertThat(client.createOptions.initialModelId()).contains("codex-model");
        assertThat(controller.state().session()).isPresent();
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
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("completion");
        assertThat(controller.state().selector().orElseThrow().title()).isEqualTo("Commands");
    }

    @Test
    void secondTurnReconcilesAndRetriesWhenTheRunSettlesDuringSubmission() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.submitFailure = new ProjectProductException("CODING_SESSION_ACTIVE", "Run became active");
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "second turn"));

        assertThat(client.submitAttempts).isEqualTo(2);
        assertThat(client.submittedMessages).containsExactly("second turn");
        assertThat(controller.state().editorBuffer()).isEmpty();
        assertThat(controller.state().transcript()).anyMatch(item -> item.body().equals("second turn"));
    }

    @Test
    void terminalRunEventRoutesTheNextEnterToANewTurnInsteadOfStaleSteer() {
        TerminalUiReducer reducer = new TerminalUiReducer();
        TerminalUiState active = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.SessionLoaded(activeView(), List.of("Loaded resources: project")));
        TerminalUiState settled = reducer.reduce(
                active,
                new TerminalUiAction.RunEventReceived(
                        event(1, new RunEventPayloads.RunLifecycle("COMPLETED", 2, "NONE"))));
        FakeClient client = new FakeClient(view(Optional.empty()));
        var controller = new CodingTerminalController(
                PROJECT_ID, client, new TerminalEventPump(32), reducer, settled, Runnable::run);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "next turn"));

        assertThat(client.submittedMessages).containsExactly("next turn");
        assertThat(client.steeredMessages).isEmpty();
        assertThat(controller.state().editorBuffer()).isEmpty();
    }

    @Test
    void revisionMutationReconcilesTheAuthoritativeSessionBeforeRename() {
        FakeClient client = new FakeClient(view(Optional.empty(), 0, "session"));
        client.reconciledView = view(Optional.empty(), 7, "session");
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/rename reconciled-name"));

        assertThat(client.renamedExpectedRevision).isEqualTo(7);
        assertThat(client.renamedDisplayName).isEqualTo("reconciled-name");
        assertThat(controller.state().session().orElseThrow().summary().displayName())
                .isEqualTo("reconciled-name");
    }

    @Test
    void productFailureStaysInTheTerminalAndPreservesTheDraft() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.submitFailure = new ProjectProductException("SESSION_NOT_FOUND", "Session unavailable");
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "keep this draft"));

        assertThat(controller.state().recoverableError()).contains("SESSION_NOT_FOUND");
        assertThat(controller.state().editorBuffer()).isEqualTo("keep this draft");
        assertThat(controller.state().editorCursor()).isEqualTo("keep this draft".length());
    }

    @Test
    void escapeCancelsAnActiveRunEvenWhenASelectorIsOpen() {
        FakeClient client = new FakeClient(activeView());
        TerminalUiReducer reducer = new TerminalUiReducer();
        TerminalUiState activeState = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.SessionLoaded(activeView(), List.of("Loaded resources: project")));
        activeState = reducer.reduce(activeState, new TerminalUiAction.EditorChanged("draft", 5));
        activeState = reducer.reduce(
                activeState,
                new TerminalUiAction.SelectorOpened(
                        new io.haifa.agent.application.coding.terminal.state.TerminalSelector(
                                "completion", "Commands", List.of("/resume"), 0)));
        var controller = new CodingTerminalController(
                PROJECT_ID, client, new TerminalEventPump(32), reducer, activeState, Runnable::run);

        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, "draft"));

        assertThat(client.cancelledSessions).containsExactly(SESSION_ID);
        assertThat(controller.state().status()).isEqualTo("Cancelling");
        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().editorBuffer()).isEqualTo("draft");
    }

    @Test
    void escapeReconcilesAStaleIdleViewBeforeCancellingTheActiveRun() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.reconciledView = activeView();
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));

        assertThat(client.cancelledSessions).containsExactly(SESSION_ID);
        assertThat(controller.state().status()).isEqualTo("Cancelling");
    }

    @Test
    void closedEventSubscriptionReconcilesAndClearsTheStaleWorkingState() {
        FakeClient client = new FakeClient(activeView());
        client.reconciledView = view(Optional.empty());
        var controller = controller(client);
        controller.open(SESSION_ID);
        client.lastSubscription.close();

        controller.drainEvents();

        assertThat(client.reconcileCalls).isEqualTo(1);
        assertThat(client.subscriptionCount).isEqualTo(1);
        assertThat(controller.state().currentRunId()).isEmpty();
        assertThat(controller.state().status()).isEqualTo("Idle");
    }

    @Test
    void eventQueueOverflowReconcilesFromTheAuthoritativeSessionView() {
        FakeClient client = new FakeClient(activeView());
        client.reconciledView = view(Optional.empty());
        TerminalEventPump pump = new TerminalEventPump(1);
        var controller = new CodingTerminalController(
                PROJECT_ID, client, pump, new TerminalUiReducer(), TerminalUiState.initial(120, 40), Runnable::run);
        controller.open(SESSION_ID);
        assertThat(pump.offer(new TerminalUiAction.StatusChanged("first"))).isTrue();
        assertThat(pump.offer(new TerminalUiAction.StatusChanged("dropped"))).isFalse();

        controller.drainEvents();

        assertThat(client.reconcileCalls).isEqualTo(1);
        assertThat(controller.state().currentRunId()).isEmpty();
        assertThat(controller.state().status()).isEqualTo("Idle");
    }

    @Test
    void transientCursorPersistenceFailureDoesNotStopOutputAndRetriesOnTheNextTick() {
        FakeClient client = new FakeClient(activeView());
        client.acknowledgementFailuresRemaining = 1;
        var controller = controller(client);
        controller.open(SESSION_ID);
        client.emit(event(1, new RunEventPayloads.AssistantTextDelta("generation-1", "keeps rendering")));
        client.emit(event(2, new RunEventPayloads.AssistantTextDelta("generation-1", " after contention")));

        controller.drainEvents();

        assertThat(controller.state().transcript())
                .anyMatch(item -> item.body().contains("keeps rendering after contention"));
        assertThat(client.acknowledgementCalls).isEqualTo(1);

        controller.drainEvents();

        assertThat(client.acknowledgementCalls).isEqualTo(2);
        assertThat(client.acknowledgedCursor.exclusiveSequence()).isEqualTo(OptionalLong.of(2));
    }

    @Test
    void governedShellApprovalCompletesThroughTheClientAndProjectsSafeResult() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.shellState = CodingShellPlan.State.APPROVAL_REQUIRED;
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "!git status --short"));
        assertThat(controller.state().selector()).isPresent();
        controller.accept(input(TerminalInput.Kind.SELECT_PREVIOUS, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(client.shellApproved).isTrue();
        assertThat(client.shellIncludedInContext).isTrue();
        assertThat(controller.state().transcript())
                .anyMatch(item ->
                        item.kind() == io.haifa.agent.application.coding.terminal.state.TranscriptItem.Kind.EXECUTION
                                && item.body().contains("safe shell output")
                                && item.expanded());
    }

    @Test
    void doubleBangExecutesButDoesNotAppendToModelContext() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.shellState = CodingShellPlan.State.READY;
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "!!git status --short"));

        assertThat(client.shellIncludedInContext).isFalse();
        assertThat(controller.state().status()).contains("excluded from model context");
    }

    @Test
    void closingShellApprovalSelectorDiscardsThePendingRequest() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.shellState = CodingShellPlan.State.APPROVAL_REQUIRED;
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "!git status --short"));
        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));

        assertThat(client.shellDiscarded).isTrue();
        assertThat(controller.state().selector()).isEmpty();
    }

    private static CodingTerminalController controller(CodingSessionClient client) {
        return new CodingTerminalController(
                PROJECT_ID,
                client,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run);
    }

    private static CodingAuthenticationView authenticatedCodexConnection() {
        return new CodingAuthenticationView(
                "model-auth://openai-codex/default",
                "openai-codex",
                CodingAuthenticationView.Method.CHATGPT_SUBSCRIPTION,
                CodingAuthenticationView.Status.AUTHENTICATED,
                "ChatGPT account",
                Optional.empty(),
                OptionalLong.empty(),
                true);
    }

    private static InteractionView approval() {
        return new InteractionView(
                new InteractionRequestId("interaction-1"),
                new AgentRunId("run-1"),
                SESSION_ID,
                0,
                InteractionKind.APPROVAL,
                InteractionState.PENDING,
                "Approval",
                "Allow file change?",
                List.of(InteractionAction.REJECT, InteractionAction.APPROVE),
                InteractionInputContract.NONE,
                new InteractionTargetView("tool", "file-write", Optional.empty(), Optional.empty(), "workspace file"),
                new InteractionRequesterView("user", "local user"),
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60),
                new InteractionConsequenceView("Run tool", "Reject tool", "Expire request"));
    }

    private static CodingSessionView view(Optional<InteractionView> interaction) {
        return view(interaction, 0, "session");
    }

    private static CodingSessionView view(Optional<InteractionView> interaction, long revision, String displayName) {
        return new CodingSessionView(
                new CodingSessionSummary(
                        SESSION_ID,
                        PROJECT_ID,
                        displayName,
                        io.haifa.agent.core.session.AgentSessionStatus.ACTIVE,
                        Optional.empty(),
                        Optional.empty(),
                        0,
                        Instant.EPOCH,
                        revision),
                Optional.empty(),
                interaction,
                Optional.empty(),
                "sha256:configuration",
                "cli-coding@1.0.0");
    }

    private static CodingSessionView activeView() {
        AgentRunId runId = new AgentRunId("run-1");
        return new CodingSessionView(
                new CodingSessionSummary(
                        SESSION_ID,
                        PROJECT_ID,
                        "session",
                        io.haifa.agent.core.session.AgentSessionStatus.ACTIVE,
                        Optional.of(runId),
                        Optional.of(AgentRunStatus.RUNNING),
                        0,
                        Instant.EPOCH,
                        0),
                Optional.of(new AgentRunSnapshot(
                        runId,
                        AgentRunStatus.RUNNING,
                        1,
                        Instant.EPOCH,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                "sha256:configuration",
                "cli-coding@1.0.0");
    }

    private static TerminalInput input(TerminalInput.Kind kind, String text) {
        return new TerminalInput(kind, text);
    }

    private static AgentRunEvent event(long sequence, AgentRunEvent.Payload payload) {
        AgentRunId runId = new AgentRunId("run-1");
        return new AgentRunEvent(
                "event-" + sequence,
                "assistant.text.delta",
                "1",
                runId,
                SESSION_ID,
                sequence,
                new RunEventCursor(runId, "1", OptionalLong.of(sequence)),
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty(),
                payload);
    }

    private static final class FakeClient implements CodingSessionClient {
        private CodingSessionView view;
        private CodingSessionView reconciledView;
        private List<CodingSessionSummary> summaries = List.of();
        private CodingSessionHistoryPage history = CodingSessionHistoryPage.empty(SESSION_ID);
        private List<CodingQueuedMessage> restorable = List.of();
        private List<String> logicalPaths = List.of();
        private List<CodingModelOption> models = List.of();
        private CodingSessionCreateOptions createOptions = CodingSessionCreateOptions.defaults();
        private ProjectProductException submitFailure;
        private int submitAttempts;
        private int listLimit;
        private final List<String> submittedMessages = new ArrayList<>();
        private final List<String> steeredMessages = new ArrayList<>();
        private final List<AgentSessionId> opened = new ArrayList<>();
        private final List<String> restored = new ArrayList<>();
        private final List<InteractionAction> respondedActions = new ArrayList<>();
        private final List<AgentSessionId> cancelledSessions = new ArrayList<>();
        private CodingShellPlan.State shellState = CodingShellPlan.State.READY;
        private boolean shellApproved;
        private boolean shellIncludedInContext;
        private boolean shellDiscarded;
        private long renamedExpectedRevision = -1;
        private String renamedDisplayName;
        private int reconcileCalls;
        private int pendingInteractionCalls;
        private int subscriptionCount;
        private RunEventSubscription lastSubscription;
        private AgentRunEventListener lastListener;
        private int acknowledgementFailuresRemaining;
        private int acknowledgementCalls;
        private RunEventCursor acknowledgedCursor;

        private FakeClient(CodingSessionView view) {
            this.view = view;
            this.reconciledView = view;
        }

        @Override
        public CodingSessionView create(ProjectId projectId, String firstTurn, String idempotencyKey) {
            return view;
        }

        @Override
        public CodingSessionView create(
                ProjectId projectId, String firstTurn, String idempotencyKey, CodingSessionCreateOptions options) {
            createOptions = options;
            return view;
        }

        @Override
        public List<CodingModelOption> models() {
            return models;
        }

        @Override
        public List<CodingSessionSummary> list(ProjectId projectId, int limit) {
            listLimit = limit;
            return summaries;
        }

        @Override
        public CodingSessionHistoryPage history(AgentSessionId sessionId, int limit) {
            return history;
        }

        @Override
        public CodingSessionView open(AgentSessionId sessionId) {
            opened.add(sessionId);
            return view;
        }

        @Override
        public CodingSessionView reconcile(AgentSessionId sessionId) {
            reconcileCalls++;
            view = reconciledView;
            return reconciledView;
        }

        @Override
        public Optional<AgentRunSnapshot> findRun(AgentRunId runId) {
            return view.activeRun().filter(snapshot -> snapshot.runId().equals(runId));
        }

        @Override
        public void submit(AgentSessionId sessionId, String message, String idempotencyKey) {
            submitAttempts++;
            if (submitFailure != null) {
                ProjectProductException failure = submitFailure;
                submitFailure = null;
                throw failure;
            }
            submittedMessages.add(message);
        }

        @Override
        public void steer(AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey) {
            steeredMessages.add(message);
        }

        @Override
        public void enqueueFollowUp(
                AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey) {}

        @Override
        public List<CodingQueuedMessage> restorableMessages(AgentSessionId sessionId, int limit) {
            return restorable;
        }

        @Override
        public CodingRestoredMessage restore(AgentSessionId sessionId, String followUpId, long revision) {
            restored.add(followUpId);
            restorable = List.of();
            return new CodingRestoredMessage(followUpId, sessionId, "queued task", List.of(), revision + 1);
        }

        @Override
        public Optional<InteractionView> pendingInteraction(AgentRunId runId) {
            pendingInteractionCalls++;
            return reconciledView.pendingInteraction();
        }

        @Override
        public InteractionResponseReceipt respond(
                InteractionView interaction, InteractionAction action, String idempotencyKey) {
            respondedActions.add(action);
            return new InteractionResponseReceipt(
                    new InteractionResponseId("response-1"),
                    interaction.requestId(),
                    interaction.runId(),
                    InteractionResponseReceiptStatus.NEWLY_ACCEPTED,
                    InteractionState.RESPONDED,
                    interaction.revision() + 1,
                    1);
        }

        @Override
        public void cancel(AgentSessionId sessionId, String idempotencyKey) {
            cancelledSessions.add(sessionId);
        }

        @Override
        public CodingSessionSummary rename(AgentSessionId sessionId, String displayName, long expectedRevision) {
            renamedExpectedRevision = expectedRevision;
            renamedDisplayName = displayName;
            CodingSessionView renamed = view(Optional.empty(), expectedRevision + 1, displayName);
            view = renamed;
            reconciledView = renamed;
            return renamed.summary();
        }

        @Override
        public CodingShellPlan planShell(AgentSessionId sessionId, String command, boolean includeInContext) {
            shellIncludedInContext = includeInContext;
            return new CodingShellPlan("shell-token", sessionId, command, includeInContext, shellState, "TEST_POLICY");
        }

        @Override
        public CodingShellResult executeShell(String token, boolean approved) {
            shellApproved = approved;
            return new CodingShellResult(
                    "SUCCEEDED",
                    Optional.of(0),
                    "safe shell output",
                    Optional.of("output-ref"),
                    false,
                    shellIncludedInContext);
        }

        @Override
        public void discardShell(String token) {
            shellDiscarded = true;
        }

        @Override
        public RunEventPage events(AgentRunId runId, RunEventCursor after, int limit) {
            return new RunEventPage(List.of(), after, after, false);
        }

        @Override
        public RunEventCursor acknowledgeCursor(AgentSessionId sessionId, RunEventCursor cursor) {
            acknowledgementCalls++;
            if (acknowledgementFailuresRemaining > 0) {
                acknowledgementFailuresRemaining--;
                throw new IllegalStateException("transient cursor persistence failure");
            }
            acknowledgedCursor = cursor;
            return cursor;
        }

        @Override
        public RunEventSubscription subscribe(AgentRunId runId, RunEventCursor after, AgentRunEventListener listener) {
            subscriptionCount++;
            lastListener = listener;
            lastSubscription = new RunEventSubscription() {
                private boolean closed;

                @Override
                public boolean closed() {
                    return closed;
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
            return lastSubscription;
        }

        private void emit(AgentRunEvent event) {
            lastListener.onEvent(event);
        }

        @Override
        public List<String> logicalPaths() {
            return logicalPaths;
        }
    }
}
