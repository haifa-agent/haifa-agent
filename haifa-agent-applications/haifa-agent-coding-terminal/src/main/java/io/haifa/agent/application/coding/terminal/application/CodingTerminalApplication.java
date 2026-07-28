package io.haifa.agent.application.coding.terminal.application;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.session.CodingSessionClient;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.tui4j.Tui4jCodingTerminal;
import io.haifa.agent.application.coding.terminal.tui4j.Tui4jTerminalIo;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import java.util.Objects;
import java.util.Optional;

/** Runnable tui4j product shell. Assembly supplies the already-authorized product client. */
public final class CodingTerminalApplication {
    private static final int ACTION_QUEUE_CAPACITY = 1_024;
    private static final int DEFAULT_COLUMNS = 80;
    private static final int DEFAULT_ROWS = 24;

    private final ProjectId projectId;
    private final CodingSessionClient client;
    private final Optional<AgentSessionId> resumeSession;
    private final Tui4jTerminalIo terminalIo;

    public CodingTerminalApplication(
            ProjectId projectId, CodingSessionClient client, Optional<AgentSessionId> resumeSession) {
        this(projectId, client, resumeSession, Tui4jTerminalIo.system());
    }

    public CodingTerminalApplication(
            ProjectId projectId,
            CodingSessionClient client,
            Optional<AgentSessionId> resumeSession,
            Tui4jTerminalIo terminalIo) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.resumeSession = Objects.requireNonNull(resumeSession, "resumeSession must not be null");
        this.terminalIo = Objects.requireNonNull(terminalIo, "terminalIo must not be null");
    }

    public void run() {
        TerminalEventPump pump = new TerminalEventPump(ACTION_QUEUE_CAPACITY);
        var controller = new CodingTerminalController(
                projectId,
                client,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(DEFAULT_COLUMNS, DEFAULT_ROWS));
        try (controller) {
            resumeSession.ifPresent(controller::open);
            new Tui4jCodingTerminal(controller, pump, terminalIo).run();
        }
    }
}
