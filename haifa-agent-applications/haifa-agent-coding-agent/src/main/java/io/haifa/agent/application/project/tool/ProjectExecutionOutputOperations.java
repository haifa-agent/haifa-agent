package io.haifa.agent.application.project.tool;

import io.haifa.agent.application.project.product.coding.execution.CodingExecutionOutputAccess;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Model-facing bounded WINDOW/SEARCH projection over trusted retained execution output. */
public final class ProjectExecutionOutputOperations {
    public static final String TOOL_NAME = "execution.output.read";
    public static final String MODEL_ALIAS = "execution_output_read";
    static final int MAXIMUM_WINDOW_BYTES = 3072;
    static final int MAXIMUM_MATCHES = 20;
    private static final int DEFAULT_WINDOW_BYTES = 2048;
    private static final int DEFAULT_MATCHES = 10;

    private final CodingExecutionOutputAccess access;

    public ProjectExecutionOutputOperations(CodingExecutionOutputAccess access) {
        this.access = Objects.requireNonNull(access, "access must not be null");
    }

    public ToolResult execute(ToolInvocationRequest invocation) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        invocation.observer().dispatched();
        ToolResult result;
        try {
            var request = request(invocation);
            result = success(access.read(request));
        } catch (IllegalArgumentException exception) {
            result = failure(
                    CodingExecutionOutputAccess.FailureCode.INVALID_TOOL_ARGUMENT,
                    safeMessage(exception, "Invalid execution output read arguments."));
        } catch (CodingExecutionOutputAccess.AccessException exception) {
            result = failure(exception.code(), safeMessage(exception, "Execution output cannot be read."));
        }
        invocation.observer().acknowledged();
        return result;
    }

    private static CodingExecutionOutputAccess.ReadRequest request(ToolInvocationRequest invocation) {
        Map<String, Object> values = invocation.arguments().values();
        String outputRef = requiredText(values, "outputRef", 512);
        CodingExecutionOutputAccess.Mode mode;
        try {
            mode = CodingExecutionOutputAccess.Mode.valueOf(
                    requiredText(values, "mode", 16).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("mode must be WINDOW or SEARCH");
        }
        if (mode == CodingExecutionOutputAccess.Mode.WINDOW) {
            rejectPresent(values, "query", "maximumMatches");
            long offset = integer(values, "offsetBytes", 0, 0, Long.MAX_VALUE);
            int maximumBytes = (int) integer(values, "maximumBytes", DEFAULT_WINDOW_BYTES, 1, MAXIMUM_WINDOW_BYTES);
            return new CodingExecutionOutputAccess.ReadRequest(
                    invocation.tenant(),
                    invocation.principal(),
                    invocation.runId(),
                    outputRef,
                    mode,
                    offset,
                    maximumBytes,
                    "",
                    0);
        }
        rejectPresent(values, "offsetBytes", "maximumBytes");
        String query = requiredText(values, "query", 256);
        int maximumMatches = (int) integer(values, "maximumMatches", DEFAULT_MATCHES, 1, MAXIMUM_MATCHES);
        return new CodingExecutionOutputAccess.ReadRequest(
                invocation.tenant(),
                invocation.principal(),
                invocation.runId(),
                outputRef,
                mode,
                0,
                0,
                query,
                maximumMatches);
    }

    private static ToolResult success(CodingExecutionOutputAccess.ReadResult result) {
        var data = new LinkedHashMap<String, Object>();
        data.put("outputRef", result.outputRef());
        data.put("mode", result.mode().name());
        data.put("hasMore", result.hasMore());
        data.put("retainedBytes", result.retainedBytes());
        data.put("captureTruncated", result.captureTruncated());
        String summary;
        if (result.mode() == CodingExecutionOutputAccess.Mode.WINDOW) {
            data.put("text", result.text());
            data.put("startOffsetBytes", result.startOffsetBytes());
            data.put("endOffsetBytes", result.endOffsetBytes());
            if (result.nextOffsetBytes() != null) data.put("nextOffsetBytes", result.nextOffsetBytes());
            summary = "Execution output window: outputRef="
                    + result.outputRef()
                    + ", bytes="
                    + result.startOffsetBytes()
                    + ".."
                    + result.endOffsetBytes()
                    + ", retainedBytes="
                    + result.retainedBytes()
                    + ", captureTruncated="
                    + result.captureTruncated()
                    + ", hasMore="
                    + result.hasMore()
                    + (result.nextOffsetBytes() == null ? "" : ", nextOffsetBytes=" + result.nextOffsetBytes())
                    + "\n"
                    + result.text();
        } else {
            List<Map<String, Object>> matches = result.matches().stream()
                    .map(match -> Map.<String, Object>of("byteOffset", match.byteOffset(), "snippet", match.snippet()))
                    .toList();
            data.put("matches", matches);
            data.put("matchesReturned", matches.size());
            summary = "Execution output search: outputRef="
                    + result.outputRef()
                    + ", matchesReturned="
                    + matches.size()
                    + ", retainedBytes="
                    + result.retainedBytes()
                    + ", captureTruncated="
                    + result.captureTruncated()
                    + ", hasMore="
                    + result.hasMore();
            if (matches.isEmpty() && result.captureTruncated()) {
                summary +=
                        "\nNo match was found in retained output; capture was truncated, so absence is inconclusive.";
            }
            for (var match : result.matches()) {
                summary += "\nbyteOffset=" + match.byteOffset() + ": " + match.snippet();
            }
        }
        if (summary.length() > 4000) {
            throw new IllegalStateException("bounded execution output summary exceeded its envelope");
        }
        return new ToolResult(true, summary, Map.copyOf(data), List.of(), List.of(), false);
    }

    private static ToolResult failure(CodingExecutionOutputAccess.FailureCode code, String summary) {
        return new ToolResult(
                false,
                summary,
                Map.of(
                        "status",
                        "FAILED",
                        "failureCategory",
                        code == CodingExecutionOutputAccess.FailureCode.INVALID_TOOL_ARGUMENT
                                ? "INVALID_INPUT"
                                : "RESOURCE",
                        "stableFailureCode",
                        code.name(),
                        "resourceClass",
                        "EXECUTION_OUTPUT"),
                List.of(),
                List.of(),
                false);
    }

    private static void rejectPresent(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key)) throw new IllegalArgumentException(key + " is not valid for this mode");
        }
    }

    private static String requiredText(Map<String, Object> values, String key, int maximumLength) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximumLength) {
            throw new IllegalArgumentException(
                    key + " must be non-empty text of at most " + maximumLength + " characters");
        }
        return text;
    }

    private static long integer(Map<String, Object> values, String key, long defaultValue, long minimum, long maximum) {
        Object value = values.get(key);
        if (value == null) return defaultValue;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        long parsed = number.longValue();
        if (number.doubleValue() != parsed || parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(key + " must be an integer from " + minimum + " to " + maximum);
        }
        return parsed;
    }

    private static String safeMessage(Exception exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
