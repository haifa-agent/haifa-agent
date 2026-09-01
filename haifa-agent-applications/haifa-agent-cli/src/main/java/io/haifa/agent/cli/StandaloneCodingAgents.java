package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.client.CodingAgentClientFactory;
import io.haifa.agent.application.project.product.coding.client.LocalCodingSessionClient;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Public highest-layer assembly entry for the standalone Coding Agent product. */
public final class StandaloneCodingAgents {
    private StandaloneCodingAgents() {}

    /** Public highest-level factory for callers that inject the standard product client contract. */
    public static CodingAgentClientFactory factory() {
        return StandaloneCodingAgents::open;
    }

    public static StandaloneCodingAgent open(Path workspace) {
        return openResolved(normalizeWorkspace(workspace), Optional.empty());
    }

    public static StandaloneCodingAgent open(Path workspace, Path configuration) {
        return openResolved(
                normalizeWorkspace(workspace), Optional.of(normalizeConfiguration(configuration)), System.getenv());
    }

    public static StandaloneCodingAgent open(Path workspace, Path configuration, Map<String, String> environment) {
        return openResolved(
                normalizeWorkspace(workspace),
                Optional.of(normalizeConfiguration(configuration)),
                Map.copyOf(Objects.requireNonNull(environment, "environment must not be null")));
    }

    private static StandaloneCodingAgent openResolved(Path workspace, Optional<Path> configuration) {
        return openResolved(workspace, configuration, System.getenv());
    }

    private static StandaloneCodingAgent openResolved(
            Path workspace, Optional<Path> configuration, Map<String, String> environment) {
        CliArguments arguments = new CliArguments(
                Optional.empty(),
                Optional.empty(),
                Optional.of(workspace),
                configuration,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                false,
                false,
                false);
        CliConfiguration resolved = new CliConfigurationLoader(environment::get).load(arguments, workspace);
        return assemble(
                workspace,
                resolved,
                LocalCodingAgent.createWithTrace(
                        workspace,
                        resolved,
                        new PrintStream(OutputStream.nullOutputStream()),
                        ignored -> {},
                        environment));
    }

    static StandaloneCodingAgent open(
            Path workspace,
            CliConfiguration configuration,
            PrintStream output,
            Consumer<RuntimeTraceEvent> traceObserver) {
        return assemble(
                normalizeWorkspace(workspace),
                configuration,
                LocalCodingAgent.createWithTrace(normalizeWorkspace(workspace), configuration, output, traceObserver));
    }

    static StandaloneCodingAgent open(
            Path workspace,
            CliConfiguration configuration,
            PrintStream output,
            AgentChatModel model,
            Consumer<RuntimeTraceEvent> traceObserver) {
        Path normalized = normalizeWorkspace(workspace);
        return assemble(
                normalized,
                configuration,
                LocalCodingAgent.create(normalized, configuration, output, model, traceObserver));
    }

    static StandaloneCodingAgent open(
            Path workspace,
            CliConfiguration configuration,
            PrintStream output,
            AgentChatModel model,
            Consumer<RuntimeTraceEvent> traceObserver,
            ModelContinuationProtector continuationProtector) {
        Path normalized = normalizeWorkspace(workspace);
        return assemble(
                normalized,
                configuration,
                LocalCodingAgent.create(
                        normalized, configuration, output, model, traceObserver, continuationProtector));
    }

    private static StandaloneCodingAgent assemble(
            Path workspace, CliConfiguration configuration, LocalCodingAgent localAgent) {
        try {
            var client = new LocalCodingSessionClient(
                    localAgent.projectId(),
                    localAgent.codingSessions(),
                    localAgent.sessionHistory(),
                    localAgent.runtime(),
                    localAgent.identifiers(),
                    localAgent.time(),
                    new LocalWorkspacePathCatalog(workspace)::list,
                    localAgent::loadedResources,
                    localAgent::reloadResources,
                    localAgent.shell(),
                    localAgent.exporter(),
                    localAgent.outcomes());
            return new StandaloneCodingAgent(localAgent, client, metadata(configuration));
        } catch (RuntimeException exception) {
            localAgent.close();
            throw exception;
        }
    }

    private static StandaloneCodingAgentMetadata metadata(CliConfiguration configuration) {
        CliConfiguration.Model model = configuration.model();
        List<String> tools = configuration.enabledTools().stream().sorted().toList();
        List<String> mcpServers = configuration.mcpServers().stream()
                .map(CliConfiguration.McpServer::id)
                .sorted()
                .toList();
        List<String> skills =
                configuration.skills().allowedAliases().stream().sorted().toList();
        String canonical = String.join(
                "\n",
                "schema=standalone-coding-agent-assembly-v1",
                "providerId=" + model.providerId(),
                "modelId=" + model.modelId(),
                "modelBindingId=" + model.id(),
                "apiStyle=" + model.style().value(),
                "dialect=" + model.dialect(),
                "tools=" + String.join(",", tools),
                "mcp=" + String.join(",", mcpServers),
                "skills=" + String.join(",", skills),
                "executionProvider=" + configuration.execution().provider(),
                "executionNetwork=" + configuration.execution().network(),
                "executionShell=" + configuration.execution().shell(),
                "approval=" + configuration.approval().name(),
                "approval-threshold=" + configuration.approvalThreshold().name(),
                "persistence=" + configuration.persistence().mode().name(),
                "protection=" + configuration.persistence().protection().name(),
                "maxIterations=" + configuration.maxIterations(),
                "maxToolCalls=" + configuration.maxToolCalls());
        return new StandaloneCodingAgentMetadata(
                model.providerId(), model.modelId(), model.id(), model.style().value(), sha256(canonical));
    }

    private static Path normalizeWorkspace(Path workspace) {
        return Objects.requireNonNull(workspace, "workspace must not be null")
                .toAbsolutePath()
                .normalize();
    }

    private static Path normalizeConfiguration(Path configuration) {
        return Objects.requireNonNull(configuration, "configuration must not be null")
                .toAbsolutePath()
                .normalize();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
