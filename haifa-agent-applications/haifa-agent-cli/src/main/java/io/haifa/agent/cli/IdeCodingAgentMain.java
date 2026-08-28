package io.haifa.agent.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * IDE/debugger-friendly assembled entry point for the Coding Agent product.
 *
 * <p>Run {@link #main(String[])} directly from an IDE and step through the full assembly,
 * task submission, streaming output, and approval loop. Unlike {@link HaifaCliMain#main}
 * this entry never calls {@link System#exit}, so a non-zero result does not terminate the
 * debugger session. It is intentionally one-shot only: an explicit coding task
 * ({@code -m/--message}) is required and Terminal/resume modes are rejected.
 *
 * <h2>Configuration tiers</h2>
 *
 * <ol>
 *   <li><b>FROZEN (fixed in code)</b> — the product assembly and safety defaults from
 *       {@link CliConfiguration#defaults()}: tool catalog bindings, Skill platform,
 *       host-guarded execution sandbox, DeepSeek thinking disabled, approval {@code ask},
 *       in-memory persistence, and the no-secret output policy. Not user-configurable.</li>
 *   <li><b>YAML (medium frequency)</b> — automatic discovery loads
 *       {@code ~/.haifa-agent/coding/ide-config.yaml} (user level), then overlays
 *       {@code <workspace>/.haifa-agent/coding/ide-config.yaml} (workspace level). An explicit
 *       {@code --config <path>} replaces automatic discovery. Covers {@code models.providers}
 *       (endpoint, credentialRef, API style bindings), {@code tools.enabled}, {@code web},
 *       {@code mcp}, {@code skills}, {@code execution}, {@code approval}, {@code runtime},
 *       {@code persistence}.</li>
 *   <li><b>RUNTIME (per run)</b> — {@code --workspace}, {@code -m/--message}, {@code --model}
 *       (or {@code HAIFA_MODEL_ID}), {@code --approval}, {@code --timeout}, {@code --verbose};
 *       optional {@code --trace}/{@code --trace-file}; environment overrides supported by the
 *       loader still apply.</li>
 * </ol>
 *
 * <p>The resolved one-shot path is delegated to {@link HaifaCliMain}, so assembly, exit codes,
 * streaming, and approval handling have a single implementation.
 */
public final class IdeCodingAgentMain {
    private IdeCodingAgentMain() {}

    /** IDE entry: same as the CLI entry but never terminates the JVM. */
    public static void main(String[] arguments) {
        run(arguments, System.out, System.err);
    }

    static int run(String[] arguments, PrintStream output, PrintStream error) {
        return run(arguments, output, error, IdeCodingAgentMain::loadConfiguration, new HaifaCliMain()::run);
    }

    static CliConfiguration loadConfiguration(CliArguments arguments, Path workspace) {
        String userHome = System.getProperty("user.home");
        Optional<Path> userConfiguration = userHome == null || userHome.isBlank()
                ? Optional.empty()
                : Optional.of(Path.of(userHome, ".haifa-agent", "coding", "ide-config.yaml"));
        return new CliConfigurationLoader()
                .load(arguments, workspace, userConfiguration, Path.of(".haifa-agent", "coding", "ide-config.yaml"));
    }

    static int run(
            String[] arguments,
            PrintStream output,
            PrintStream error,
            ConfigurationResolver configurationResolver,
            ResolvedCliRunner cliRunner) {
        CliArguments parsed;
        try {
            parsed = CliArguments.parse(arguments);
        } catch (IllegalArgumentException exception) {
            error.println("Invalid command: " + exception.getMessage());
            error.println(usage());
            return 1;
        }
        if (parsed.help()) {
            output.println(usage());
            return 0;
        }
        if (parsed.terminal() || parsed.resume().isPresent()) {
            error.println(
                    "Invalid command: IdeCodingAgentMain is one-shot only; use haifa-coding for Terminal/resume.");
            error.println(usage());
            return 1;
        }
        if (parsed.message().isEmpty()) {
            error.println("Invalid command: IdeCodingAgentMain requires an explicit coding task (-m/--message).");
            error.println(usage());
            return 1;
        }
        Path workspace = parsed.workspace().orElseGet(() -> Path.of("."));
        if (!workspace.isAbsolute()) workspace = workspace.toAbsolutePath().normalize();
        CliConfiguration configuration;
        try {
            configuration = configurationResolver.load(parsed, workspace);
        } catch (IllegalArgumentException exception) {
            error.println("Invalid configuration: " + exception.getMessage());
            return 1;
        }
        output.println(summary(configuration, workspace));
        return cliRunner.run(parsed, workspace, configuration, output, error);
    }

    /** Resolved-assembly summary for debugging. Never prints endpoints or credential references. */
    static String summary(CliConfiguration configuration, Path workspace) {
        CliConfiguration.Model model = configuration.model();
        CliConfiguration.Execution execution = configuration.execution();
        String tools = configuration.enabledTools().stream().sorted().collect(Collectors.joining(", "));
        String skills =
                configuration.skills().allowedAliases().stream().sorted().collect(Collectors.joining(", "));
        return """
                Coding Agent assembly (debug entry)
                  workspace   : %s
                  model       : %s / %s (%s, %s)
                  tools       : %s
                  skills      : %s
                  execution   : %s provider, network=%s, shell=%s
                  approval    : %s (threshold=%s)
                  persistence : %s
                  runtime     : timeout=%s, maxIterations=%d, maxToolCalls=%d
                  config tiers: FROZEN assembly defaults < user ~/.haifa-agent/coding/ide-config.yaml
                                 < workspace .haifa-agent/coding/ide-config.yaml < --config <path>;
                                 per-run flags override (--model/--approval/--timeout).
                """
                .formatted(
                        workspace,
                        model.providerId(),
                        model.modelId(),
                        model.style().value(),
                        model.dialect(),
                        tools,
                        skills,
                        execution.provider(),
                        execution.network(),
                        execution.shell(),
                        configuration.approval().name(),
                        configuration.approvalThreshold().name(),
                        configuration.persistence().mode().name(),
                        configuration.timeout(),
                        configuration.maxIterations(),
                        configuration.maxToolCalls());
    }

    static String usage() {
        return """
                Usage: IdeCodingAgentMain --workspace <path> -m "<coding task>" [options]

                IDE/debugger one-shot entry for the Coding Agent. Never calls System.exit, so a
                debugger can step through assembly, the run loop, and cleanup without forced exit.

                Required:
                  -m, --message <task>     The coding task to run (one-shot; no interactive Terminal)

                Options:
                      --workspace <path>   Workspace root (default: current directory)
                      --config <path>      YAML configuration file (replaces automatic discovery)
                      --model <model-id>   Override configured model (also HAIFA_MODEL_ID)
                      --approval <mode>    ask=LOW, auto=NEVER, or deny (default: ask)
                      --timeout <duration> ISO-8601 duration, e.g. PT5M
                      --trace <mode>       summary, detail, or jsonl
                      --trace-file <path>  Write trace to a file
                      --verbose            Print lifecycle details
                  -h, --help               Show this help

                Configuration tiers:
                  1. FROZEN (code)         Assembly & safety defaults: tool catalog, skills,
                                           host-guarded sandbox, DeepSeek thinking disabled,
                                           approval=ask, memory persistence.
                  2. YAML (medium-freq.)   Auto-discovery overlays
                                           <workspace>/.haifa-agent/coding/ide-config.yaml on
                                           ~/.haifa-agent/coding/ide-config.yaml;
                                           --config <path> replaces automatic user/workspace discovery.
                                           Covers models.providers, tools.enabled, web, mcp, skills,
                                           execution, approval, runtime, persistence.
                  3. RUNTIME (args/env)    Per-run values: workspace, message, --model/HAIFA_MODEL_ID,
                                           --approval, --timeout, trace, --verbose.
                """;
    }

    @FunctionalInterface
    interface ConfigurationResolver {
        CliConfiguration load(CliArguments arguments, Path workspace);
    }

    @FunctionalInterface
    interface ResolvedCliRunner {
        int run(
                CliArguments arguments,
                Path workspace,
                CliConfiguration configuration,
                PrintStream output,
                PrintStream error);
    }
}
