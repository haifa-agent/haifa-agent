package io.haifa.agent.cli;

import io.haifa.agent.application.coding.terminal.application.CodingTerminalApplication;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalConnectionOptions;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalStartup;
import io.haifa.agent.application.coding.terminal.state.TerminalWorkspaceContext;
import io.haifa.agent.application.coding.terminal.tui4j.Tui4jTerminalIo;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.cli.remote.RemoteCodingClientInfo;
import io.haifa.agent.cli.remote.RemoteCodingSessionClient;
import io.haifa.agent.execution.api.ToolOutputPreviewPublisher;
import java.io.PrintStream;
import java.net.URI;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Highest-layer assembly for the remote TUI: a thin client of one running CA Host.
 *
 * <p>Nothing here reads the client's Git repository, credentials, model configuration or database: the Host owns
 * the workspace, Runtime, persistence and credentials. The workspace line is therefore empty unless the Host
 * publishes it, and the terminal runs with {@link CodingTerminalConnectionOptions#remoteV1()} so preview,
 * session consent and credential setup are refused up front instead of failing mid-interaction.
 */
final class RemoteCodingTerminalRunner implements CliRemoteTerminalRunner {
    private final Function<URI, RemoteCodingSessionClient> connector;
    private final Supplier<Tui4jTerminalIo> terminalIoFactory;

    RemoteCodingTerminalRunner() {
        this(RemoteCodingSessionClient::connect, Tui4jTerminalIo::system);
    }

    RemoteCodingTerminalRunner(
            Function<URI, RemoteCodingSessionClient> connector, Supplier<Tui4jTerminalIo> terminalIoFactory) {
        this.connector = Objects.requireNonNull(connector, "connector must not be null");
        this.terminalIoFactory = Objects.requireNonNull(terminalIoFactory, "terminalIoFactory must not be null");
    }

    @Override
    public void run(URI endpoint, CodingTerminalStartup startup, PrintStream output) {
        Tui4jTerminalIo terminalIo = Objects.requireNonNull(terminalIoFactory.get(), "terminal IO must not be null");
        terminalIo.requireInteractive();
        try (RemoteCodingSessionClient client = connector.apply(endpoint)) {
            RemoteCodingClientInfo info = client.info();
            output.println("Remote CA Host connected: " + endpoint);
            output.println("Host: " + info.hostName() + "  Project: "
                    + info.projectId().value());
            output.println("Preview, session consent and credential setup are owned by the Host and unavailable here.");
            new CodingTerminalApplication(
                            client.projectId(),
                            client,
                            startup,
                            terminalIo,
                            // The client owns no workspace, so the UI shows an empty workspace line rather than
                            // reading this process's Git state or configuration.
                            TerminalWorkspaceContext.empty(),
                            client.authentication(),
                            ToolOutputPreviewPublisher.noop(),
                            CodingTerminalConnectionOptions.remoteV1())
                    .run();
        } catch (ProjectProductException failure) {
            output.println("[" + failure.code() + "] " + failure.getMessage());
            throw failure;
        }
    }
}
