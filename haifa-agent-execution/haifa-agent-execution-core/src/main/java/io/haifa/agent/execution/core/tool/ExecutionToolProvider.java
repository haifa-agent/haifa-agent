package io.haifa.agent.execution.core.tool;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionInput;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.tool.api.ToolCancellation;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared Tool provider that is the only command/script adapter above ExecutionBroker. */
public final class ExecutionToolProvider implements ToolProvider {
    public static final ToolProviderId PROVIDER_ID = new ToolProviderId("haifa-execution");
    private static final int BROKER_OUTPUT_BYTES_PER_CHANNEL = 16 * 1024 * 1024;

    private final ExecutionBroker broker;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;
    private final ExecutionInvocationScopeResolver scopes;
    private final ExecutionToolConfiguration configuration;

    public ExecutionToolProvider(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            TimeProvider time,
            ExecutionInvocationScopeResolver scopes,
            ExecutionToolConfiguration configuration) {
        this.broker = Objects.requireNonNull(broker, "broker must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.scopes = Objects.requireNonNull(scopes, "scopes must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    @Override
    public ToolProviderId id() {
        return PROVIDER_ID;
    }

    public java.util.Set<String> scriptLanguages() {
        return configuration.runtimes().languages();
    }

    public String configurationIdentity() {
        return configuration.identityDigest();
    }

    @Override
    public ToolResult invoke(ToolInvocationRequest invocation) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        if (!"execution.run".equals(invocation.binding().definition().name().value())) {
            throw new IllegalArgumentException("unsupported execution tool");
        }
        var scope = scopes.resolve(invocation);
        if (!scope.capabilities().contains("execution.run")) {
            throw new SecurityException("execution.run is not authorized by the invocation scope");
        }
        ParsedInvocation parsed = parse(invocation.arguments().values());
        Duration remaining = Duration.between(time.now(), invocation.deadline());
        if (remaining.isZero() || remaining.isNegative()) {
            throw new IllegalStateException("tool invocation deadline has expired");
        }
        Duration timeout = parsed.timeout.compareTo(remaining) <= 0 ? parsed.timeout : remaining;
        WorkspacePath workingDirectory = new WorkspacePath(
                scope.workspaceId(), parsed.workdir.equals(".") ? ProjectPath.root() : ProjectPath.of(parsed.workdir));
        ExecutionRequest request = new ExecutionRequest(
                new ExecutionId(identifiers.nextValue()),
                invocation
                        .idempotencyKey()
                        .orElseGet(() -> invocation.runId().value() + ":"
                                + invocation.toolCallId().value()),
                new TrustedExecutionContext(
                        invocation.tenant(),
                        invocation.runId().value(),
                        invocation.principal(),
                        scope.capabilities(),
                        invocation
                                .policyDecisionRef()
                                .orElseThrow(() ->
                                        new SecurityException("execution tool requires a public policy decision"))),
                scope.workspaceId(),
                workingDirectory,
                parsed.command,
                configuration.environmentRef(),
                new ExecutionLimits(
                        timeout,
                        BROKER_OUTPUT_BYTES_PER_CHANNEL,
                        BROKER_OUTPUT_BYTES_PER_CHANNEL,
                        configuration.maximumProcesses()),
                configuration.sandboxProfileRef(),
                parsed.input,
                io.haifa.agent.tool.api.ToolArgumentsDigest.sha256(invocation.arguments()));
        invocation.observer().dispatched();
        ToolResult result = execute(request, invocation.cancellation(), parsed);
        invocation.observer().acknowledged();
        return result;
    }

    private ParsedInvocation parse(Map<String, Object> values) {
        String mode = text(values, "mode", 16).toUpperCase(Locale.ROOT);
        String content = text(values, "content", 16_384);
        String purpose = text(values, "purpose", 256);
        List<String> arguments = arguments(values.get("args"));
        long timeoutMillis = number(
                values.get("timeoutMillis"),
                configuration.defaultTimeout().toMillis(),
                1000,
                configuration.maximumTimeout().toMillis());
        String workdir = ".";
        if (values.containsKey("workdir")) {
            if (!configuration.workingDirectoryAllowed()) {
                throw new IllegalArgumentException("workdir is unavailable for this product");
            }
            workdir = text(values, "workdir", 4096);
        }
        return switch (mode) {
            case "COMMAND" -> {
                if (values.containsKey("language")) {
                    throw new IllegalArgumentException("language is only valid for SCRIPT mode");
                }
                if (!arguments.isEmpty()) throw new IllegalArgumentException("args are only valid for SCRIPT mode");
                yield new ParsedInvocation(
                        mode,
                        "",
                        content,
                        purpose,
                        workdir,
                        Duration.ofMillis(timeoutMillis),
                        ExecutionCommand.shell(content),
                        ExecutionInput.none());
            }
            case "SCRIPT" -> {
                String language = text(values, "language", 32).toLowerCase(Locale.ROOT);
                ScriptRuntimeAdapter.PreparedScript prepared =
                        configuration.runtimes().resolve(language).prepare(content, arguments);
                yield new ParsedInvocation(
                        mode,
                        language,
                        content,
                        purpose,
                        workdir,
                        Duration.ofMillis(timeoutMillis),
                        prepared.command(),
                        prepared.input());
            }
            default -> throw new IllegalArgumentException("mode must be COMMAND or SCRIPT");
        };
    }

    private ToolResult execute(ExecutionRequest request, ToolCancellation cancellation, ParsedInvocation parsed) {
        MergedTailObserver merged = new MergedTailObserver(
                configuration.outputObserver(), configuration.maximumOutputBytes(), configuration.maximumOutputLines());
        AtomicBoolean complete = new AtomicBoolean();
        Thread watcher = Thread.ofVirtual()
                .name("haifa-execution-tool-cancellation")
                .start(() -> {
                    while (!complete.get()) {
                        if (cancellation.isCancellationRequested() && broker.cancel(request.id())) return;
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                });
        try {
            return toToolResult(broker.execute(request, merged), merged, parsed);
        } finally {
            complete.set(true);
            watcher.interrupt();
        }
    }

    private ToolResult toToolResult(ExecutionResult result, MergedTailObserver merged, ParsedInvocation parsed) {
        String stdout = sanitizeSummary(result.stdout().summary());
        String stderr = sanitizeSummary(result.stderr().summary());
        String observed = merged.text();
        if (stdout.isBlank() && stderr.isBlank() && !observed.isBlank()) {
            stdout = sanitizeSummary(observed);
        }
        boolean truncated = merged.truncated()
                || result.stdout().truncated()
                || result.stderr().truncated();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", result.status().name());
        data.put("mode", parsed.mode);
        if (!parsed.language.isEmpty()) data.put("language", parsed.language);
        result.optionalExitCode().ifPresent(value -> data.put("exitCode", value));
        data.put("timedOut", result.status() == ExecutionStatus.TIMED_OUT);
        data.put("cancelled", result.status() == ExecutionStatus.CANCELLED);
        data.put("stdoutSummary", stdout);
        data.put("stderrSummary", stderr);
        data.put("truncated", truncated);
        data.put("durationMillis", result.resourceUsage().wallTime().toMillis());
        String headline =
                switch (result.status()) {
                    case SUCCEEDED -> parsed.mode.equals("SCRIPT") ? "Script succeeded" : "Command succeeded";
                    case FAILED -> parsed.mode.equals("SCRIPT") ? "Script failed" : "Command failed";
                    case TIMED_OUT -> "Execution timed out";
                    case CANCELLED -> "Execution was cancelled";
                    case UNKNOWN -> "Execution outcome is unknown";
                };
        if (result.exitCode() != null) headline += " (exit " + result.exitCode() + ")";
        String output = stdout.isBlank() ? stderr : stdout;
        String summary = output.isBlank() ? headline : headline + "\n" + output;
        return new ToolResult(
                result.status() == ExecutionStatus.SUCCEEDED,
                summary,
                Map.copyOf(data),
                List.of(),
                List.of(),
                truncated);
    }

    private String sanitizeSummary(String value) {
        String bounded =
                MergedTailObserver.bound(value, configuration.maximumOutputBytes(), configuration.maximumOutputLines());
        return Objects.requireNonNull(
                configuration.outputSanitizer().apply(bounded), "outputSanitizer must not return null");
    }

    private static String text(Map<String, Object> values, String key, int maximumLength) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be non-empty text");
        }
        if (text.length() > maximumLength) throw new IllegalArgumentException(key + " exceeds maximum length");
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException(key + " contains NUL");
        return text;
    }

    private static List<String> arguments(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.size() > 16) {
            throw new IllegalArgumentException("args must be an array with at most 16 items");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.length() > 1024 || text.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("args contains an invalid item");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static long number(Object value, long fallback, long minimum, long maximum) {
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException("timeoutMillis must be a number");
        long result = number.longValue();
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException("timeoutMillis is out of range");
        }
        return result;
    }

    private record ParsedInvocation(
            String mode,
            String language,
            String content,
            String purpose,
            String workdir,
            Duration timeout,
            ExecutionCommand command,
            ExecutionInput input) {}

    private static final class MergedTailObserver implements ExecutionOutputObserver {
        private final ExecutionOutputObserver delegate;
        private final byte[] tail;
        private final int maximumLines;
        private long count;
        private long lines;
        private boolean upstreamTruncated;

        private MergedTailObserver(ExecutionOutputObserver delegate, int maximumBytes, int maximumLines) {
            this.delegate = delegate;
            tail = new byte[maximumBytes];
            this.maximumLines = maximumLines;
        }

        @Override
        public synchronized void onOutput(ProcessOutputChunk chunk) {
            upstreamTruncated |= chunk.truncated();
            try {
                delegate.onOutput(chunk);
            } catch (RuntimeException ignored) {
                // Rendering or transport observers do not own the authoritative Tool result.
            }
            for (byte value : chunk.bytes()) {
                tail[(int) (count % tail.length)] = value;
                count++;
                if (value == '\n') lines++;
            }
        }

        private synchronized String text() {
            int length = (int) Math.min(count, tail.length);
            byte[] result = new byte[length];
            if (count <= tail.length) {
                System.arraycopy(tail, 0, result, 0, length);
            } else {
                int start = (int) (count % tail.length);
                int first = tail.length - start;
                System.arraycopy(tail, start, result, 0, first);
                System.arraycopy(tail, 0, result, first, start);
            }
            return keepLastLines(sanitize(new String(result, StandardCharsets.UTF_8)), maximumLines);
        }

        private synchronized boolean truncated() {
            return upstreamTruncated || count > tail.length || lines > maximumLines;
        }

        private static String keepLastLines(String value, int maximumLines) {
            int newlineCount = 0;
            for (int index = value.length() - 1; index >= 0; index--) {
                if (value.charAt(index) == '\n' && ++newlineCount > maximumLines) {
                    return value.substring(index + 1);
                }
            }
            return value;
        }

        private static String bound(String value, int maximumBytes, int maximumLines) {
            byte[] bytes = Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8);
            int start = Math.max(0, bytes.length - maximumBytes);
            while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) start++;
            return keepLastLines(
                    sanitize(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8)), maximumLines);
        }

        private static String sanitize(String value) {
            String withoutAnsi = value.replaceAll("\\u001B\\[[;?0-9]*[ -/]*[@-~]", "");
            StringBuilder safe = new StringBuilder(withoutAnsi.length());
            withoutAnsi.codePoints().forEach(codePoint -> {
                if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t' || !Character.isISOControl(codePoint)) {
                    safe.appendCodePoint(codePoint);
                }
            });
            return safe.toString();
        }
    }
}
