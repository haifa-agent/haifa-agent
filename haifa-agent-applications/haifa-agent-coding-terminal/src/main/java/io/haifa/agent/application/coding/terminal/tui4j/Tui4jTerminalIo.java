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

/** Terminal IO selection used by production system terminals and deterministic stream tests. */
public record Tui4jTerminalIo(
        Optional<InputStream> input,
        Optional<OutputStream> output,
        List<String> environment,
        boolean withoutSignalHandler) {

    public Tui4jTerminalIo {
        input = Objects.requireNonNull(input, "input must not be null");
        output = Objects.requireNonNull(output, "output must not be null");
        environment = List.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
        if (input.isPresent() != output.isPresent()) {
            throw new IllegalArgumentException("custom terminal input and output must be supplied together");
        }
    }

    public static Tui4jTerminalIo system() {
        return new Tui4jTerminalIo(Optional.empty(), Optional.empty(), List.of(), false);
    }

    public static Tui4jTerminalIo streams(InputStream input, OutputStream output, List<String> environment) {
        return new Tui4jTerminalIo(
                Optional.of(Objects.requireNonNull(input, "input must not be null")),
                Optional.of(Objects.requireNonNull(output, "output must not be null")),
                environment,
                true);
    }

    Program program(Model model) {
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
