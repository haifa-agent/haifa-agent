package io.haifa.agent.cli;

import io.haifa.agent.application.coding.terminal.application.CodingTerminalApplication;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalConnectionOptions;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalStartup;
import io.haifa.agent.application.coding.terminal.state.TerminalWorkspaceContext;
import io.haifa.agent.application.coding.terminal.tui4j.Tui4jTerminalIo;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.cli.remote.RemoteCodingClientInfo;
import io.haifa.agent.cli.remote.RemoteCodingSessionClient;
import io.haifa.coding.client.CodingClients;
import java.io.PrintStream;
import java.net.URI;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Highest-layer assembly for the remote TUI: a thin client of one running CA Host.
 *
 * <p>Nothing here reads the client's Git repository, credentials, model configuration or database: the Host owns
 * the workspace, Runtime, persistence and credentials. Workspace display facts come only from the Host
 * handshake. The terminal receives negotiated preview support; session consent and credential setup
 * remain independently disabled before any secret is collected.
 */
final class RemoteCodingTerminalRunner implements CliRemoteTerminalRunner {
    private final Function<URI, RemoteCodingSessionClient> connector;
    private final Supplier<Tui4jTerminalIo> terminalIoFactory;

    RemoteCodingTerminalRunner() {
        // A thin client must never translate its own exit signal into a Run cancel: the Host owns the Run and only
        // an explicit cancel request from this client may stop it.
        this(RemoteCodingSessionClient::connect, Tui4jTerminalIo::systemWithoutSignalHandler);
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
        try (var product = LocalCodingTerminalRunner.productPresentationEnabled()
                        ? CodingClients.connect(endpoint)
                        : null;
                RemoteCodingSessionClient client = connector.apply(endpoint)) {
            RemoteCodingClientInfo info = client.info();
            output.println("Remote CA Host connected: " + endpoint);
            output.println("Host: " + info.hostName() + "  Project: "
                    + info.projectId().value());
            output.println("Tool output preview: "
                    + (info.optionalCapabilities().contains("preview-v1")
                            ? "enabled (Host)"
                            : "unavailable (Host/client compatibility)"));
            output.println(
                    info.optionalCapabilities().contains("session-consent-v1")
                            ? "Session consent uses a client lease; credential setup stays on the trusted Host."
                            : "Session consent and credential setup are unavailable in this remote UI.");
            new CodingTerminalApplication(
                            client.projectId(),
                            client,
                            startup,
                            terminalIo,
                            workspaceContext(info),
                            client.authentication(),
                            client.previewPublisher(),
                            CodingTerminalConnectionOptions.remoteV1(
                                            info.workspace()
                                                    .map(value -> value.executionDirectory())
                                                    .orElse("unavailable (older Host)"),
                                            info.optionalCapabilities().contains("preview-v1"),
                                            info.optionalCapabilities().contains("session-consent-v1"))
                                    .withCredentialSetupInstructions(
                                            client.authentication()
                                                    .localManagementCommand()
                                                    .map(
                                                            command -> "Host: " + endpoint + "\nProject: "
                                                                    + info.projectId()
                                                                            .value()
                                                                    + "\nAccount: default slot for the provider named below"
                                                                    + "\nRun on the Host as its same OS user:\n"
                                                                    + command
                                                                    + "\nFor a local-development distribution use haifa-coding instead of haifa."
                                                                    + "\nThen reopen /account in this UI to refresh status; retry deliberately.")
                                                    .orElse(
                                                            "This Host does not advertise a locally managed default credential. "
                                                                    + "Inspect /account and the Host's configured credential source; remote secret entry stays unavailable.")))
                    .run(product);
        } catch (ProjectProductException failure) {
            output.println("[" + failure.code() + "] " + failure.getMessage());
            throw failure;
        }
    }

    static TerminalWorkspaceContext workspaceContext(RemoteCodingClientInfo info) {
        return info.workspace()
                .map(value -> new TerminalWorkspaceContext(
                        "Host: " + value.workingDirectory(),
                        value.gitBranch().isBlank() ? "unavailable (non-Git or detached)" : value.gitBranch(),
                        value.worktree().isBlank() ? "" : "Host: " + value.worktree(),
                        value.worktree().isBlank()
                                ? ""
                                : value.worktreeBranch().isBlank()
                                        ? "unavailable (detached or unreadable)"
                                        : value.worktreeBranch()))
                .orElseGet(() -> new TerminalWorkspaceContext(
                        "Host workspace unavailable (older Host)", "Git metadata unavailable", "", ""));
    }
}
