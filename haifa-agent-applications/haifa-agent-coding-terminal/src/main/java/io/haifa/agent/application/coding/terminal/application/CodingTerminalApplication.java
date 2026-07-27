package io.haifa.agent.application.coding.terminal.application;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.jline.JLineDisplayAdapter;
import io.haifa.agent.application.coding.terminal.jline.JLineEditor;
import io.haifa.agent.application.coding.terminal.jline.JLineTerminalLifecycle;
import io.haifa.agent.application.coding.terminal.session.CodingSessionClient;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.view.TerminalRenderer;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Runnable JLine product shell. Assembly supplies the already-authorized product client. */
public final class CodingTerminalApplication {
    private static final int ACTION_QUEUE_CAPACITY = 1_024;

    private final ProjectId projectId;
    private final CodingSessionClient client;
    private final Optional<AgentSessionId> resumeSession;
    private final Supplier<JLineTerminalLifecycle> lifecycleFactory;

    public CodingTerminalApplication(
            ProjectId projectId, CodingSessionClient client, Optional<AgentSessionId> resumeSession) {
        this(projectId, client, resumeSession, JLineTerminalLifecycle::openSystem);
    }

    public CodingTerminalApplication(
            ProjectId projectId,
            CodingSessionClient client,
            Optional<AgentSessionId> resumeSession,
            Supplier<JLineTerminalLifecycle> lifecycleFactory) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.resumeSession = Objects.requireNonNull(resumeSession, "resumeSession must not be null");
        this.lifecycleFactory = Objects.requireNonNull(lifecycleFactory, "lifecycleFactory must not be null");
    }

    public void run() {
        TerminalEventPump pump = new TerminalEventPump(ACTION_QUEUE_CAPACITY);
        try (JLineTerminalLifecycle lifecycle =
                Objects.requireNonNull(lifecycleFactory.get(), "terminal lifecycle must not be null")) {
            var terminal = lifecycle.terminal();
            var size = terminal.getSize();
            var controller = new CodingTerminalController(
                    projectId,
                    client,
                    pump,
                    new TerminalUiReducer(),
                    TerminalUiState.initial(size.getColumns(), size.getRows()));
            lifecycle.installSignalHandlers(
                    resized ->
                            pump.offer(new TerminalUiAction.TerminalResized(resized.getColumns(), resized.getRows())),
                    () -> pump.offer(new TerminalUiAction.RecoverableFailure("INTERRUPT_REQUESTED")));
            lifecycle.enterRawMode();
            var display = new JLineDisplayAdapter(terminal);
            var renderer = new TerminalRenderer();
            var editor = new JLineEditor(terminal, client::logicalPaths);
            try (controller) {
                resumeSession.ifPresent(controller::open);
                while (!controller.state().exitRequested()) {
                    controller.drainEvents();
                    display.render(renderer.render(controller.state()));
                    controller.accept(editor.read(
                            controller.state().editorBuffer(),
                            controller.state().selector().isPresent()));
                }
            } finally {
                display.reset();
            }
        }
    }
}
