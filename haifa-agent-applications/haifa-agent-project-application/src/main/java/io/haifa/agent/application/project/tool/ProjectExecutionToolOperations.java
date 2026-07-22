package io.haifa.agent.application.project.tool;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adapts the generic project Tool invocation to the single trusted ExecutionBroker path. */
public final class ProjectExecutionToolOperations {
    private static final int FULL_OUTPUT_BYTES_PER_CHANNEL = 16 * 1024 * 1024;
    private static final int SUMMARY_OUTPUT_CHARS = 12 * 1024;

    private final ExecutionBroker broker;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;
    private final ExecutionEnvironmentRef environmentRef;
    private final SandboxProfileRef sandboxProfileRef;
    private final Duration defaultTimeout;
    private final Duration maximumTimeout;
    private final int maximumModelOutputBytes;
    private final int maximumModelOutputLines;
    private final int maximumProcesses;
    private final ExecutionOutputObserver outputObserver;

    public ProjectExecutionToolOperations(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            TimeProvider time,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef sandboxProfileRef,
            Duration defaultTimeout,
            Duration maximumTimeout,
            int maximumModelOutputBytes,
            int maximumModelOutputLines,
            int maximumProcesses,
            ExecutionOutputObserver outputObserver) {
        this.broker = Objects.requireNonNull(broker, "broker must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.environmentRef = Objects.requireNonNull(environmentRef, "environmentRef must not be null");
        this.sandboxProfileRef = Objects.requireNonNull(sandboxProfileRef, "sandboxProfileRef must not be null");
        this.defaultTimeout = positive(defaultTimeout, "defaultTimeout");
        this.maximumTimeout = positive(maximumTimeout, "maximumTimeout");
        if (defaultTimeout.compareTo(maximumTimeout) > 0) {
            throw new IllegalArgumentException("defaultTimeout exceeds maximumTimeout");
        }
        if (maximumTimeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("maximumTimeout exceeds the execution API limit");
        }
        if (maximumModelOutputBytes < 1024 || maximumModelOutputBytes > 1024 * 1024) {
            throw new IllegalArgumentException("maximumModelOutputBytes is out of range");
        }
        if (maximumModelOutputLines < 1 || maximumModelOutputLines > 10_000) {
            throw new IllegalArgumentException("maximumModelOutputLines is out of range");
        }
        if (maximumProcesses < 1 || maximumProcesses > 64) {
            throw new IllegalArgumentException("maximumProcesses is out of range");
        }
        this.maximumModelOutputBytes = maximumModelOutputBytes;
        this.maximumModelOutputLines = maximumModelOutputLines;
        this.maximumProcesses = maximumProcesses;
        this.outputObserver = Objects.requireNonNull(outputObserver, "outputObserver must not be null");
    }

    public ToolResult execute(ToolInvocationRequest invocation, RunWorkspaceAccess access) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(access, "access must not be null");
        Map<String, Object> arguments = invocation.arguments().values();
        String command = requiredText(arguments, "command");
        String workdir = optionalText(arguments, "workdir", ".");
        Duration requestedTimeout = Duration.ofMillis(
                optionalLong(arguments, "timeoutMillis", defaultTimeout.toMillis(), 1, maximumTimeout.toMillis()));
        Duration remaining = Duration.between(time.now(), invocation.deadline());
        if (remaining.isZero() || remaining.isNegative()) {
            throw new IllegalStateException("tool invocation deadline has expired");
        }
        Duration timeout = requestedTimeout.compareTo(remaining) <= 0 ? requestedTimeout : remaining;
        ExecutionId executionId = new ExecutionId(identifiers.nextValue());
        WorkspacePath workingDirectory = new WorkspacePath(
                access.workspaceId(), workdir.equals(".") ? ProjectPath.root() : ProjectPath.of(workdir));
        ExecutionRequest request = new ExecutionRequest(
                executionId,
                invocation
                        .idempotencyKey()
                        .orElseGet(() -> invocation.runId().value() + ":"
                                + invocation.toolCallId().value()),
                new TrustedExecutionContext(
                        invocation.runId().value(),
                        invocation.principal(),
                        access.capabilities(),
                        access.policyDecisionRef()),
                access.workspaceId(),
                workingDirectory,
                ExecutionCommand.shell(command),
                environmentRef,
                new ExecutionLimits(
                        timeout, FULL_OUTPUT_BYTES_PER_CHANNEL, FULL_OUTPUT_BYTES_PER_CHANNEL, maximumProcesses),
                sandboxProfileRef);
        MergedTailObserver merged =
                new MergedTailObserver(outputObserver, maximumModelOutputBytes, maximumModelOutputLines);
        AtomicBoolean complete = new AtomicBoolean();
        Thread cancellation = Thread.ofVirtual()
                .name("haifa-execution-cancellation")
                .start(() -> {
                    while (!complete.get()) {
                        if (invocation.cancellation().isCancellationRequested()) {
                            if (broker.cancel(executionId)) return;
                        }
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                });
        try {
            return toToolResult(broker.execute(request, merged), merged);
        } finally {
            complete.set(true);
            cancellation.interrupt();
        }
    }

    private static ToolResult toToolResult(ExecutionResult result, MergedTailObserver merged) {
        String output = merged.text();
        if (output.isBlank()) output = MergedTailObserver.sanitize(fallbackOutput(result));
        boolean truncated = merged.truncated()
                || result.stdout().truncated()
                || result.stderr().truncated();
        var data = new LinkedHashMap<String, Object>();
        data.put("executionId", result.id().value());
        data.put("status", result.status().name());
        result.optionalExitCode().ifPresent(value -> data.put("exitCode", value));
        data.put("output", output);
        data.put("truncated", truncated);
        data.put("durationMillis", result.resourceUsage().wallTime().toMillis());
        result.optionalFileChangeSetId().ifPresent(value -> data.put("fileChangeSetId", value.value()));
        result.optionalFailure().ifPresent(value -> {
            data.put("failureCode", value.code());
            data.put("failureDetail", value.safeDetail());
        });
        List<AssetRef> assets = new ArrayList<>();
        result.stdout().optionalAssetRef().ifPresent(assets::add);
        result.stderr().optionalAssetRef().ifPresent(assets::add);
        if (!assets.isEmpty()) {
            data.put("outputRef", assets.getFirst().assetId());
            data.put("outputRefs", assets.stream().map(AssetRef::assetId).toList());
        }
        String headline =
                switch (result.status()) {
                    case SUCCEEDED -> "Command succeeded";
                    case FAILED -> "Command failed";
                    case TIMED_OUT -> "Command timed out";
                    case CANCELLED -> "Command was cancelled";
                    case UNKNOWN -> "Command outcome is unknown";
                };
        if (result.exitCode() != null) headline += " (exit " + result.exitCode() + ")";
        String summaryOutput = output.length() <= SUMMARY_OUTPUT_CHARS
                ? output
                : "<output truncated; showing tail>\n" + output.substring(output.length() - SUMMARY_OUTPUT_CHARS);
        String summary = summaryOutput.isBlank() ? headline : headline + "\n" + summaryOutput;
        return new ToolResult(
                result.status() == ExecutionStatus.SUCCEEDED,
                summary,
                Map.copyOf(data),
                List.copyOf(assets),
                List.of(),
                truncated);
    }

    private static String fallbackOutput(ExecutionResult result) {
        String stdout = result.stdout().summary();
        String stderr = result.stderr().summary();
        if (stdout.isBlank()) return stderr;
        if (stderr.isBlank()) return stdout;
        return stdout + "\n" + stderr;
    }

    private static String requiredText(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be non-empty text");
        }
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException(key + " contains NUL");
        return text;
    }

    private static String optionalText(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be non-empty text");
        }
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException(key + " contains NUL");
        return text;
    }

    private static long optionalLong(
            Map<String, Object> values, String key, long fallback, long minimum, long maximum) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be a number");
        long result = number.longValue();
        if (result < minimum || result > maximum) throw new IllegalArgumentException(key + " is out of range");
        return result;
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

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
                // CLI rendering errors cannot remove output from the authoritative Tool result.
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
                if (value.charAt(index) != '\n') continue;
                newlineCount++;
                if (newlineCount > maximumLines) return value.substring(index + 1);
            }
            return value;
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
