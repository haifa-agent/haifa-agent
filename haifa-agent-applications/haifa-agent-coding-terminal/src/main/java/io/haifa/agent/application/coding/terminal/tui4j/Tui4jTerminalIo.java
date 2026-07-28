package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.compat.bubbletea.Model;
import com.williamcallahan.tui4j.compat.bubbletea.Program;
import com.williamcallahan.tui4j.compat.bubbletea.ProgramOption;
import com.williamcallahan.tui4j.input.kitty.KittyEnterKeyMappings;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Terminal IO selection used by production system terminals and deterministic stream tests. */
public record Tui4jTerminalIo(
        Optional<InputStream> input,
        Optional<OutputStream> output,
        List<String> environment,
        boolean withoutSignalHandler,
        boolean interactive) {
    private static final Set<String> SAFE_TERMINAL_ENVIRONMENT = Set.of(
            "COLORTERM",
            "NO_COLOR",
            "TERM",
            "TERM_PROGRAM",
            "TERM_PROGRAM_VERSION",
            "TMUX",
            "WT_SESSION",
            "WSL_DISTRO_NAME");

    public Tui4jTerminalIo {
        input = Objects.requireNonNull(input, "input must not be null");
        output = Objects.requireNonNull(output, "output must not be null");
        environment = List.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
        if (input.isPresent() != output.isPresent()) {
            throw new IllegalArgumentException("custom terminal input and output must be supplied together");
        }
    }

    public Tui4jTerminalIo(
            Optional<InputStream> input,
            Optional<OutputStream> output,
            List<String> environment,
            boolean withoutSignalHandler) {
        this(input, output, environment, withoutSignalHandler, true);
    }

    public static Tui4jTerminalIo system() {
        List<String> environment = System.getenv().entrySet().stream()
                .filter(entry -> SAFE_TERMINAL_ENVIRONMENT.contains(entry.getKey()))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .sorted()
                .toList();
        return new Tui4jTerminalIo(Optional.empty(), Optional.empty(), environment, false, System.console() != null);
    }

    public static Tui4jTerminalIo streams(InputStream input, OutputStream output, List<String> environment) {
        return new Tui4jTerminalIo(
                Optional.of(Objects.requireNonNull(input, "input must not be null")),
                Optional.of(Objects.requireNonNull(output, "output must not be null")),
                environment,
                true,
                true);
    }

    public void requireInteractive() {
        if (!interactive || value("TERM").filter("dumb"::equalsIgnoreCase).isPresent()) {
            throw new IllegalStateException(Tui4jCodingTerminal.TUI_UNAVAILABLE);
        }
    }

    Optional<String> compatibilityNotice() {
        if (value("WT_SESSION").isPresent()) {
            return Optional.of("WINDOWS_TERMINAL_MODIFIED_ENTER_REMAP");
        }
        String program = value("TERM_PROGRAM").orElse("").toLowerCase(java.util.Locale.ROOT);
        if (program.contains("wezterm")) return Optional.of("WEZTERM_OPTION_ENTER_REMAP");
        if (program.contains("alacritty")) return Optional.of("ALACRITTY_OPTION_ENTER_REMAP");
        if (program.contains("apple_terminal")) return Optional.of("APPLE_TERMINAL_MODIFIED_ENTER_LIMITED");
        if (program.contains("jetbrains") || program.contains("jediterm")) {
            return Optional.of("MODIFIED_ENTER_UNAVAILABLE");
        }
        String term = value("TERM").orElse("").toLowerCase(java.util.Locale.ROOT);
        if (term.contains("xfce") || term.contains("terminator")) {
            return Optional.of("MODIFIED_ENTER_UNAVAILABLE");
        }
        return Optional.empty();
    }

    private Optional<String> value(String name) {
        String prefix = name + "=";
        return environment.stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()))
                .findFirst();
    }

    Program program(Model model) {
        requireInteractive();
        List<ProgramOption> options = new ArrayList<>();
        input.ifPresent(value -> options.add(ProgramOption.withInput(value)));
        output.ifPresent(value -> options.add(ProgramOption.withOutput(value)));
        if (!environment.isEmpty()) {
            options.add(ProgramOption.withEnvironment(environment));
        }
        if (withoutSignalHandler) {
            options.add(ProgramOption.withoutSignalHandler());
        }
        options.add(KittyEnterKeyMappings.withKittyEnterKeyMappings());
        return new Program(model, options.toArray(ProgramOption[]::new))
                .withAltScreen()
                .withKittyKeyboard();
    }
}
