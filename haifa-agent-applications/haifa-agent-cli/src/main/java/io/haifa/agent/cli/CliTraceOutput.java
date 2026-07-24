package io.haifa.agent.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Live, bounded and redaction-safe CLI projection of {@link RuntimeTraceEvent}. */
final class CliTraceOutput implements Consumer<RuntimeTraceEvent>, AutoCloseable {
    private static final Set<String> SUMMARY_OPERATIONS = Set.of(
            "model.invoke",
            "model.error",
            "model.context-too-long",
            "context.forced-rebuild",
            "tool.execute",
            "tool.persisted");
    private static final Set<String> BLOCKED_KEY_PARTS = Set.of(
            "prompt", "secret", "apikey", "api_key", "arguments", "rawresponse", "credential", "reasoningcontent");
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[;?0-9]*[ -/]*[@-~]");
    private static final int MAX_STRING_LENGTH = 512;
    private static final int MAX_COLLECTION_SIZE = 64;
    private static final int MAX_DEPTH = 8;

    private final Optional<CliTraceMode> mode;
    private final PrintStream terminal;
    private final BufferedWriter file;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, ToolIdentity> tools = new ConcurrentHashMap<>();
    private IOException failure;

    private CliTraceOutput(Optional<CliTraceMode> mode, PrintStream terminal, BufferedWriter file) {
        this.mode = mode;
        this.terminal = terminal;
        this.file = file;
    }

    static CliTraceOutput open(Optional<CliTraceMode> mode, Optional<Path> traceFile, PrintStream terminal) {
        if (mode.isEmpty() && traceFile.isPresent()) {
            throw new IllegalArgumentException("--trace-file requires --trace");
        }
        if (mode.isEmpty()) return new CliTraceOutput(Optional.empty(), terminal, null);
        if (traceFile.isEmpty()) return new CliTraceOutput(mode, terminal, null);

        Path target = traceFile.orElseThrow().toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw new IllegalArgumentException("trace file parent must be an existing non-symlink directory");
        }
        if (Files.isSymbolicLink(target)
                || (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalArgumentException("trace file must be a regular non-symlink file");
        }
        try {
            BufferedWriter writer = Files.newBufferedWriter(
                    target,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            return new CliTraceOutput(mode, terminal, writer);
        } catch (IOException exception) {
            throw new IllegalArgumentException("trace file cannot be opened: " + target, exception);
        }
    }

    @Override
    public synchronized void accept(RuntimeTraceEvent event) {
        if (mode.isEmpty() || failure != null) return;
        String rendered =
                switch (mode.orElseThrow()) {
                    case SUMMARY -> summary(event);
                    case DETAIL -> detail(event);
                    case JSONL -> jsonl(event);
                };
        if (rendered == null) return;
        if (file == null) {
            terminal.println(rendered);
            terminal.flush();
            return;
        }
        try {
            file.write(rendered);
            file.newLine();
            file.flush();
        } catch (IOException exception) {
            failure = exception;
        }
    }

    private String summary(RuntimeTraceEvent event) {
        rememberTool(event);
        if (!SUMMARY_OPERATIONS.contains(event.operation())) return null;
        Map<String, Object> attributes = safeAttributes(event.safeAttributes());
        String prefix = "[trace] " + event.occurredAt() + " run="
                + safeText(event.runId().value()) + " iteration=" + event.iteration();
        return switch (event.operation()) {
            case "model.invoke" ->
                prefix + " model completed"
                        + selected(
                                attributes,
                                "providerId",
                                "modelId",
                                "finishReason",
                                "inputTokens",
                                "outputTokens",
                                "reasoningTokens");
            case "model.error", "model.context-too-long" ->
                prefix + " model failed"
                        + selected(
                                attributes,
                                "providerId",
                                "modelId",
                                "category",
                                "retryable",
                                "httpStatus",
                                "exceptionType");
            case "context.forced-rebuild" ->
                prefix + " context rebuilt" + selected(attributes, "forcedRebuildAttempt", "estimatedInputTokens");
            case "tool.execute" ->
                prefix + " " + toolKind(attributes.get("providerId")) + " started"
                        + selected(attributes, "toolName", "providerId")
                        + optionalField(
                                "toolCallId",
                                event.toolCallId().map(value -> value.value()).orElse(null));
            case "tool.persisted" -> {
                ToolIdentity identity = event.toolCallId()
                        .map(value -> tools.get(value.value()))
                        .orElse(null);
                yield prefix + " " + toolKind(identity == null ? null : identity.providerId()) + " completed"
                        + optionalField("toolName", identity == null ? null : identity.name())
                        + selected(attributes, "successful", "truncated", "externalized")
                        + optionalField(
                                "toolCallId",
                                event.toolCallId().map(value -> value.value()).orElse(null));
            }
            default -> null;
        };
    }

    private String detail(RuntimeTraceEvent event) {
        rememberTool(event);
        StringBuilder line = new StringBuilder("[trace] ")
                .append(event.occurredAt())
                .append(" operation=")
                .append(safeText(event.operation()))
                .append(" phase=")
                .append(event.phase().name())
                .append(" runId=")
                .append(safeText(event.runId().value()))
                .append(" sessionId=")
                .append(safeText(event.sessionId().value()))
                .append(" iteration=")
                .append(event.iteration());
        event.attemptId().ifPresent(value -> line.append(" attemptId=").append(safeText(value.value())));
        event.stepId().ifPresent(value -> line.append(" stepId=").append(safeText(value.value())));
        event.toolCallId().ifPresent(value -> line.append(" toolCallId=").append(safeText(value.value())));
        event.workerId().ifPresent(value -> line.append(" workerId=").append(safeText(value)));
        line.append(" traceId=").append(safeText(event.traceId()));
        safeAttributes(event.safeAttributes())
                .forEach((key, value) ->
                        line.append(' ').append(safeText(key)).append('=').append(humanValue(value)));
        return line.toString();
    }

    private String jsonl(RuntimeTraceEvent event) {
        rememberTool(event);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("timestamp", event.occurredAt().toString());
        value.put("traceId", safeText(event.traceId()));
        value.put("runId", safeText(event.runId().value()));
        event.attemptId().ifPresent(id -> value.put("attemptId", safeText(id.value())));
        value.put("sessionId", safeText(event.sessionId().value()));
        event.stepId().ifPresent(id -> value.put("stepId", safeText(id.value())));
        event.toolCallId().ifPresent(id -> value.put("toolCallId", safeText(id.value())));
        event.workerId().ifPresent(id -> value.put("workerId", safeText(id)));
        value.put("iteration", event.iteration());
        value.put("phase", event.phase().name());
        value.put("operation", safeText(event.operation()));
        value.put("attributes", safeAttributes(event.safeAttributes()));
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("safe trace event could not be serialized", exception);
        }
    }

    private void rememberTool(RuntimeTraceEvent event) {
        if (!event.operation().equals("tool.execute") || event.toolCallId().isEmpty()) return;
        Map<String, Object> attributes = safeAttributes(event.safeAttributes());
        tools.put(
                event.toolCallId().orElseThrow().value(),
                new ToolIdentity(stringValue(attributes.get("toolName")), stringValue(attributes.get("providerId"))));
    }

    private static String selected(Map<String, Object> attributes, String... keys) {
        StringBuilder result = new StringBuilder();
        for (String key : keys) {
            if (attributes.containsKey(key)) {
                result.append(' ').append(key).append('=').append(humanValue(attributes.get(key)));
            }
        }
        return result.toString();
    }

    private static String optionalField(String key, Object value) {
        return value == null ? "" : " " + key + "=" + humanValue(sanitize(value, 0));
    }

    private static String toolKind(Object providerId) {
        String provider = stringValue(providerId);
        if ("haifa-runtime-skill".equals(provider)) return "skill";
        if (provider != null && provider.startsWith("mcp.")) return "mcp";
        return "tool";
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    private static Map<String, Object> safeAttributes(Map<String, Object> source) {
        Map<String, Object> safe = new TreeMap<>();
        source.forEach((key, value) -> {
            if (!blockedKey(key)) safe.put(safeText(key), sanitize(value, 0));
        });
        return safe;
    }

    private static Object sanitize(Object value, int depth) {
        if (value == null) return null;
        if (depth >= MAX_DEPTH) return "<max-depth>";
        if (value instanceof String text) return safeText(text);
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Enum<?> enumValue) return enumValue.name();
        if (value instanceof Optional<?> optional) {
            return optional.map(item -> sanitize(item, depth + 1)).orElse(null);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new TreeMap<>();
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .limit(MAX_COLLECTION_SIZE)
                    .forEach(entry -> {
                        String key = safeText(String.valueOf(entry.getKey()));
                        if (!blockedKey(key)) safe.put(key, sanitize(entry.getValue(), depth + 1));
                    });
            return safe;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> safe = new ArrayList<>();
            for (Object item : iterable) {
                if (safe.size() == MAX_COLLECTION_SIZE) break;
                safe.add(sanitize(item, depth + 1));
            }
            return java.util.Collections.unmodifiableList(safe);
        }
        return "<" + safeText(value.getClass().getSimpleName()) + ">";
    }

    private static boolean blockedKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (normalized.contains("reasoning")
                && !normalized.equals("reasoningtokens")
                && !normalized.equals("reasoningcharacters")) {
            return true;
        }
        return BLOCKED_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    private static String safeText(String value) {
        String withoutAnsi = ANSI_ESCAPE.matcher(value).replaceAll("");
        StringBuilder safe = new StringBuilder(Math.min(withoutAnsi.length(), MAX_STRING_LENGTH));
        withoutAnsi.codePoints().forEach(codePoint -> {
            if (safe.length() >= MAX_STRING_LENGTH) return;
            if (Character.isISOControl(codePoint)) safe.append(' ');
            else safe.appendCodePoint(codePoint);
        });
        if (withoutAnsi.length() > MAX_STRING_LENGTH) safe.append("...");
        return safe.toString();
    }

    private static String humanValue(Object value) {
        if (value instanceof String text) return text.indexOf(' ') >= 0 ? '"' + text + '"' : text;
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return new ObjectMapper().writeValueAsString(value);
            } catch (JsonProcessingException exception) {
                return "<unavailable>";
            }
        }
        return String.valueOf(value);
    }

    @Override
    public synchronized void close() {
        IOException closeFailure = null;
        if (file != null) {
            try {
                file.close();
            } catch (IOException exception) {
                closeFailure = exception;
            }
        }
        if (failure != null || closeFailure != null) {
            throw new IllegalStateException("trace output failed", failure != null ? failure : closeFailure);
        }
    }

    private record ToolIdentity(String name, String providerId) {}
}
