package io.haifa.agent.application.coding.terminal.application;

import static io.haifa.agent.application.coding.terminal.application.CodingTerminalTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.ProjectProductException;
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
    void settingsStaysUnavailableWhileTrustListsAndRevokesCurrentWorkspaceAccess() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.workspaces = List.of(
                new CodingWorkspaceView("workspace-initial", "haifa-agent", "DEVELOP", "initial", "active", false),
                new CodingWorkspaceView(
                        "workspace-docs", "haifa-agent-docs", "READ", "approved-attach", "active", true));
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
                            "haifa-agent · DEVELOP · active · initial · workspace-initial",
                            "haifa-agent-docs · READ · active · approved-attach · workspace-docs · revocable");
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
        client.models = List.of(model("default-model", "Default"), model("codex-model", "Codex"));
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/model"));

        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().selector().orElseThrow().title()).isEqualTo("Model for next session");
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("model-detail");
        assertThat(controller.state().selector().orElseThrow().title()).contains("Codex", "128K context");
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, "first message"));

        assertThat(client.createOptions.initialModelId()).contains("codex-model");
        assertThat(controller.state().session()).isPresent();
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
        assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("completion");
        assertThat(controller.state().selector().orElseThrow().title()).isEqualTo("Commands");
    }
}
