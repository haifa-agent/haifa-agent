package io.haifa.agent.application.coding.terminal.application;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.state.TerminalWorkspaceContext;
import io.haifa.agent.application.coding.terminal.tui4j.Tui4jCodingTerminal;
import io.haifa.agent.application.coding.terminal.tui4j.Tui4jTerminalIo;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
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
    private final CodingAuthenticationClient authentication;
    private final CodingTerminalStartup startup;
    private final Tui4jTerminalIo terminalIo;
    private final TerminalWorkspaceContext workspace;

    public CodingTerminalApplication(
            ProjectId projectId, CodingSessionClient client, Optional<AgentSessionId> resumeSession) {
        this(
                projectId,
                client,
                resumeSession
                        .map(value -> CodingTerminalStartup.session(value, Optional.empty()))
                        .orElseGet(CodingTerminalStartup::empty),
                Tui4jTerminalIo.system(),
                TerminalWorkspaceContext.empty(),
                CodingAuthenticationClient.unavailable());
    }

    public CodingTerminalApplication(
            ProjectId projectId,
            CodingSessionClient client,
            Optional<AgentSessionId> resumeSession,
            Tui4jTerminalIo terminalIo) {
        this(
                projectId,
                client,
                resumeSession
                        .map(value -> CodingTerminalStartup.session(value, Optional.empty()))
                        .orElseGet(CodingTerminalStartup::empty),
                terminalIo,
                TerminalWorkspaceContext.empty(),
                CodingAuthenticationClient.unavailable());
    }

    public CodingTerminalApplication(
            ProjectId projectId,
            CodingSessionClient client,
            Optional<AgentSessionId> resumeSession,
            Tui4jTerminalIo terminalIo,
            TerminalWorkspaceContext workspace) {
        this(
                projectId,
                client,
                resumeSession
                        .map(value -> CodingTerminalStartup.session(value, Optional.empty()))
                        .orElseGet(CodingTerminalStartup::empty),
                terminalIo,
                workspace,
                CodingAuthenticationClient.unavailable());
    }

    public CodingTerminalApplication(
            ProjectId projectId,
            CodingSessionClient client,
            CodingTerminalStartup startup,
            Tui4jTerminalIo terminalIo,
            TerminalWorkspaceContext workspace) {
        this(projectId, client, startup, terminalIo, workspace, CodingAuthenticationClient.unavailable());
    }

    public CodingTerminalApplication(
            ProjectId projectId,
            CodingSessionClient client,
            CodingTerminalStartup startup,
            Tui4jTerminalIo terminalIo,
            TerminalWorkspaceContext workspace,
            CodingAuthenticationClient authentication) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.authentication = Objects.requireNonNull(authentication, "authentication must not be null");
        this.startup = Objects.requireNonNull(startup, "startup must not be null");
        this.terminalIo = Objects.requireNonNull(terminalIo, "terminalIo must not be null");
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
    }

    public void run() {
        TerminalEventPump pump = new TerminalEventPump(ACTION_QUEUE_CAPACITY);
        var controller = new CodingTerminalController(
                projectId,
                client,
                authentication,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(DEFAULT_COLUMNS, DEFAULT_ROWS, workspace));
        try (controller) {
            controller.start(startup);
            new Tui4jCodingTerminal(controller, pump, terminalIo).run();
        }
    }
}
