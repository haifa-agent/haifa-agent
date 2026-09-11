package io.haifa.agent.application.coding.terminal.application;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingModelControls;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingModelPreferences;
import io.haifa.agent.application.project.product.coding.CodingModelState;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingResponseMode;
import io.haifa.agent.application.project.product.coding.CodingRestoredMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionCreateOptions;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.CodingShellPlan;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.application.project.product.coding.CodingWorkspaceView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.ModelReasoningEffort;
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
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

final class CodingTerminalTestSupport {
    static final ProjectId PROJECT_ID = new ProjectId("project-1");
    static final AgentSessionId SESSION_ID = new AgentSessionId("session-1");

    private CodingTerminalTestSupport() {}

    static CodingTerminalController controller(CodingSessionClient client, CodingAuthenticationClient authentication) {
        return new CodingTerminalController(
                PROJECT_ID,
                client,
                authentication,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run);
    }

    static CodingTerminalController controller(CodingSessionClient client) {
        return new CodingTerminalController(
                PROJECT_ID,
                client,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run);
    }

    static CodingModelOption model(String id, String displayName) {
        return new CodingModelOption(
                id,
                displayName,
                "provider",
                "Provider",
                Set.of("TEXT_CHAT", "TOOL_CALLING", "REASONING"),
                128_000,
                16_000,
                new CodingModelState(
                        CodingModelState.Connection.CONNECTED,
                        CodingModelState.BindingAvailability.AVAILABLE,
                        CodingModelState.RuntimeStatus.NORMAL,
                        CodingModelState.RunScope.IDLE),
                "",
                new CodingModelControls(
                        new CodingModelControls.ResponseModeControl(
                                "responseMode",
                                true,
                                false,
                                List.of(
                                        CodingResponseMode.FAST,
                                        CodingResponseMode.RECOMMENDED,
                                        CodingResponseMode.DEEP),
                                CodingResponseMode.RECOMMENDED,
                                "Balanced"),
                        new CodingModelControls.ReasoningEffortControl(
                                "reasoningEffort",
                                true,
                                false,
                                List.of(
                                        ModelReasoningEffort.LOW,
                                        ModelReasoningEffort.MEDIUM,
                                        ModelReasoningEffort.HIGH),
                                ModelReasoningEffort.MEDIUM,
                                "Choose from verified reasoning effort levels")),
                CodingModelPreferences.recommended(),
                Optional.empty());
    }

    static CodingModelOption unavailableModel(String id, String displayName) {
        return new CodingModelOption(
                id,
                displayName,
                "provider",
                "Provider",
                Set.of("TEXT_CHAT", "TOOL_CALLING"),
                128_000,
                16_000,
                CodingModelState.unavailable(),
                "Binding profile has not passed contract verification",
                CodingModelControls.unavailable(),
                CodingModelPreferences.recommended(),
                Optional.empty());
    }

    static CodingAuthenticationView authenticatedCodexConnection() {
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

    static InteractionView approval() {
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

    static CodingSessionView view(Optional<InteractionView> interaction) {
        return view(interaction, 0, "session");
    }

    static CodingSessionView view(Optional<InteractionView> interaction, long revision, String displayName) {
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

    static CodingSessionView activeView() {
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
                "cli-coding@1.0.0",
                new io.haifa.agent.application.project.product.coding.CodingModelSelection(
                        new io.haifa.agent.application.project.product.coding.CodingModelOption(
                                "cli-coding@1.0.0",
                                "cli-coding@1.0.0",
                                "configured",
                                "Configured",
                                java.util.Set.of(),
                                1),
                        0,
                        true),
                Optional.of("latest active task"));
    }

    static TerminalInput input(TerminalInput.Kind kind, String text) {
        return new TerminalInput(kind, text);
    }

    static AgentRunEvent event(long sequence, AgentRunEvent.Payload payload) {
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

    static final class FakeClient implements CodingSessionClient {
        CodingSessionView view;
        CodingSessionView reconciledView;
        List<CodingSessionSummary> summaries = List.of();
        CodingSessionHistoryPage history = CodingSessionHistoryPage.empty(SESSION_ID);
        List<CodingQueuedMessage> restorable = List.of();
        List<String> logicalPaths = List.of();
        List<CodingModelOption> models = List.of();
        List<CodingWorkspaceView> workspaces = List.of();
        CodingSessionCreateOptions createOptions = CodingSessionCreateOptions.defaults();
        ProjectProductException submitFailure;
        ProjectProductException responseFailure;
        int submitAttempts;
        int listCalls;
        int listLimit;
        final List<String> submittedMessages = new ArrayList<>();
        final List<String> steeredMessages = new ArrayList<>();
        final List<AgentSessionId> opened = new ArrayList<>();
        final List<String> restored = new ArrayList<>();
        final List<InteractionAction> respondedActions = new ArrayList<>();
        final List<AgentSessionId> cancelledSessions = new ArrayList<>();
        final List<String> revokedWorkspaces = new ArrayList<>();
        CodingShellPlan.State shellState = CodingShellPlan.State.READY;
        boolean shellApproved;
        boolean shellIncludedInContext;
        boolean shellDiscarded;
        long renamedExpectedRevision = -1;
        String renamedDisplayName;
        int reconcileCalls;
        int pendingInteractionCalls;
        int subscriptionCount;
        RunEventSubscription lastSubscription;
        AgentRunEventListener lastListener;
        int acknowledgementFailuresRemaining;
        int acknowledgementCalls;
        RunEventCursor acknowledgedCursor;

        FakeClient(CodingSessionView view) {
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
        public List<CodingWorkspaceView> workspaces() {
            return workspaces;
        }

        @Override
        public void revokeWorkspace(String workspaceRef) {
            revokedWorkspaces.add(workspaceRef);
        }

        @Override
        public List<CodingSessionSummary> list(ProjectId projectId, int limit) {
            listCalls++;
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
            if (responseFailure != null) {
                ProjectProductException failure = responseFailure;
                responseFailure = null;
                throw failure;
            }
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

        void emit(AgentRunEvent event) {
            lastListener.onEvent(event);
        }

        @Override
        public List<String> logicalPaths() {
            return logicalPaths;
        }
    }
}
