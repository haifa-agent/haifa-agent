package io.haifa.agent.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

record CliArguments(
        Optional<String> message,
        Optional<CliResumeRequest> resume,
        Optional<Path> workspace,
        Optional<Path> config,
        Optional<String> model,
        Optional<ApprovalMode> approval,
        Optional<Duration> timeout,
        Optional<CliTraceMode> trace,
        Optional<Path> traceFile,
        boolean terminal,
        boolean verbose,
        boolean help) {

    static CliArguments parse(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        String message = null;
        Path workspace = null;
        Path config = null;
        String model = null;
        ApprovalMode approval = null;
        Duration timeout = null;
        CliTraceMode trace = null;
        Path traceFile = null;
        boolean terminal = false;
        boolean verbose = false;
        boolean help = false;
        boolean resumeCommand = false;
        boolean resumeLast = false;
        boolean promptOnly = false;
        List<String> resumeValues = new ArrayList<>();
        List<String> values = new ArrayList<>(List.of(arguments));
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (promptOnly) {
                resumeValues.add(value);
                continue;
            }
            switch (value) {
                case "-h", "--help" -> help = true;
                case "--terminal" -> terminal = true;
                case "--verbose" -> verbose = true;
                case "-m", "--message" -> message = requireValue(values, ++index, value);
                case "--workspace" -> workspace = Path.of(requireValue(values, ++index, value));
                case "--config" -> config = Path.of(requireValue(values, ++index, value));
                case "--model" -> model = requireValue(values, ++index, value);
                case "--approval" -> approval = ApprovalMode.parse(requireValue(values, ++index, value));
                case "--timeout" -> timeout = Duration.parse(requireValue(values, ++index, value));
                case "--trace" -> trace = CliTraceMode.parse(requireValue(values, ++index, value));
                case "--trace-file" -> traceFile = Path.of(requireValue(values, ++index, value));
                case "resume" -> {
                    if (resumeCommand) throw new IllegalArgumentException("resume command may only be specified once");
                    resumeCommand = true;
                }
                case "--last" -> {
                    if (!resumeCommand) throw new IllegalArgumentException("unknown option: " + value);
                    if (resumeLast) throw new IllegalArgumentException("--last may only be specified once");
                    if (!resumeValues.isEmpty()) {
                        throw new IllegalArgumentException("--last cannot be used with a SESSION_ID");
                    }
                    resumeLast = true;
                }
                case "--" -> {
                    if (!resumeCommand) throw new IllegalArgumentException("unknown option: " + value);
                    promptOnly = true;
                }
                default -> {
                    if (!resumeCommand) throw new IllegalArgumentException("unknown option: " + value);
                    resumeValues.add(value);
                }
            }
        }
        if (traceFile != null && trace == null) {
            throw new IllegalArgumentException("--trace-file requires --trace");
        }
        if (!help && terminal && message != null) {
            throw new IllegalArgumentException("--terminal cannot be used with -m/--message");
        }
        if (!help && resumeCommand && (terminal || message != null)) {
            throw new IllegalArgumentException("resume cannot be used with --terminal or -m/--message");
        }
        if (!help && resumeCommand && model != null) {
            throw new IllegalArgumentException("resume cannot override the Session model with --model");
        }
        Optional<CliResumeRequest> resume =
                resumeCommand ? Optional.of(resume(resumeLast, resumeValues)) : Optional.empty();
        return new CliArguments(
                optionalText(message),
                resume,
                Optional.ofNullable(workspace),
                Optional.ofNullable(config),
                optionalText(model),
                Optional.ofNullable(approval),
                Optional.ofNullable(timeout),
                Optional.ofNullable(trace),
                Optional.ofNullable(traceFile),
                terminal,
                verbose,
                help);
    }

    private static CliResumeRequest resume(boolean last, List<String> values) {
        if (last) {
            return new CliResumeRequest(
                    CliResumeRequest.Target.LAST, Optional.empty(), optionalText(String.join(" ", values)));
        }
        if (values.isEmpty()) {
            return new CliResumeRequest(CliResumeRequest.Target.SELECTOR, Optional.empty(), Optional.empty());
        }
        return new CliResumeRequest(
                CliResumeRequest.Target.SESSION,
                Optional.of(new io.haifa.agent.core.session.AgentSessionId(values.getFirst())),
                optionalText(String.join(" ", values.subList(1, values.size()))));
    }

    private static String requireValue(List<String> values, int index, String option) {
        if (index >= values.size()) throw new IllegalArgumentException("missing value for " + option);
        String value = values.get(index).trim();
        if (value.isEmpty() || value.startsWith("-")) throw new IllegalArgumentException("missing value for " + option);
        return value;
    }

    private static Optional<String> optionalText(String value) {
        return value == null ? Optional.empty() : Optional.of(value.trim()).filter(text -> !text.isEmpty());
    }
}
