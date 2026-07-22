package io.haifa.agent.cli;

import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Entry point for the one-shot Haifa coding-agent command. */
public final class HaifaCliMain {
    private HaifaCliMain() {}

    public static void main(String[] arguments) {
        int exitCode = new HaifaCliMain().run(arguments, System.out, System.err);
        if (exitCode != 0) System.exit(exitCode);
    }

    int run(String[] arguments, PrintStream output, PrintStream error) {
        try {
            CliArguments parsed = CliArguments.parse(arguments);
            if (parsed.help() || parsed.message().isEmpty()) {
                output.println(usage());
                if (parsed.message().isEmpty() && !parsed.help()) {
                    output.println("Terminal UI is not implemented yet; use haifa-cli -m <task>.");
                    return 1;
                }
                return 0;
            }
            Path workspace = parsed.workspace().orElseGet(() -> Path.of("."));
            if (!workspace.isAbsolute()) workspace = workspace.toAbsolutePath().normalize();
            CliConfiguration configuration = new CliConfigurationLoader().load(parsed, workspace);
            try (LocalCodingAgent agent = LocalCodingAgent.create(workspace, configuration)) {
                if (parsed.verbose()) output.println("Submitting coding task in " + workspace.getFileName());
                var accepted = agent.start(parsed.message().orElseThrow());
                if (parsed.verbose()) output.println("Run " + accepted.runId().value() + " submitted.");
                var completed =
                        await(agent, accepted.runId(), configuration.timeout(), configuration.approval(), output);
                completed.output().ifPresent(output::println);
                if (completed.status().isTerminal()
                        && completed.status() == io.haifa.agent.core.run.AgentRunStatus.COMPLETED) {
                    return 0;
                }
                completed
                        .error()
                        .ifPresent(value ->
                                error.println("Task failed: " + value.code().value()));
                if (!completed.status().isTerminal()) error.println("Task did not complete before the CLI timeout.");
                return 2;
            }
        } catch (IllegalArgumentException exception) {
            error.println("Invalid command: " + exception.getMessage());
            error.println("Use --help for usage.");
            return 1;
        } catch (Exception exception) {
            error.println("Unable to run haifa-cli: " + exception.getClass().getSimpleName());
            return 1;
        }
    }

    private static io.haifa.agent.runtime.api.AgentRunSnapshot await(
            LocalCodingAgent agent,
            io.haifa.agent.core.run.AgentRunId runId,
            Duration timeout,
            ApprovalMode approval,
            PrintStream output)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        while (Instant.now().isBefore(deadline)) {
            var snapshot = agent.runtime().find(runId).orElseThrow();
            if (snapshot.status().isTerminal()) return snapshot;
            agent.interactions().pending(runId).ifPresent(request -> respond(agent, request, approval, input, output));
            Thread.sleep(50);
        }
        return agent.runtime().find(runId).orElseThrow();
    }

    private static void respond(
            LocalCodingAgent agent,
            io.haifa.agent.runtime.core.interaction.InteractionRequest request,
            ApprovalMode approval,
            BufferedReader input,
            PrintStream output) {
        InteractionResponseType response =
                switch (approval) {
                    case AUTO -> InteractionResponseType.APPROVE;
                    case DENY -> InteractionResponseType.REJECT;
                    case ASK ->
                        confirm(request.prompt(), input, output)
                                ? InteractionResponseType.APPROVE
                                : InteractionResponseType.REJECT;
                };
        agent.runtime()
                .respond(new InteractionResponse(
                        new InteractionResponseId(agent.identifiers().nextValue()),
                        request.id(),
                        request.runId(),
                        response,
                        List.of(),
                        "cli-interaction-" + request.id().value(),
                        agent.time().now()));
    }

    private static boolean confirm(String prompt, BufferedReader input, PrintStream output) {
        output.print(prompt + " [y/N] ");
        output.flush();
        try {
            String answer = input.readLine();
            return answer != null && answer.trim().equalsIgnoreCase("y");
        } catch (java.io.IOException exception) {
            return false;
        }
    }

    static String usage() {
        return """
                Usage: haifa-cli -m <task> [options]
                  -m, --message <task>       One-shot coding task
                      --workspace <path>     Workspace root (default: current directory)
                      --config <path>        Configuration file
                      --model <model-id>     Override configured model
                      --approval <mode>      ask, auto, or deny (default: ask)
                      --timeout <duration>   ISO-8601 duration, e.g. PT5M
                      --verbose              Print lifecycle details
                  -h, --help                 Show this help
                """;
    }
}
