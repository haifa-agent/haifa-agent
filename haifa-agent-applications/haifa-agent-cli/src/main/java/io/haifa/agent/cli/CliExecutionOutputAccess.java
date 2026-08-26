package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.execution.CodingExecutionOutputAccess;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionOutput;
import io.haifa.agent.execution.api.ExecutionStore;
import io.haifa.agent.execution.core.store.InMemoryExecutionOutputStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** CLI-local adapter over the existing retained execution output store. */
final class CliExecutionOutputAccess implements CodingExecutionOutputAccess {
    private static final String PREFIX = "execution:";
    private static final int SEARCH_BODY_BUDGET_BYTES = 2400;

    private final ExecutionStore executions;
    private final InMemoryExecutionOutputStore outputs;

    CliExecutionOutputAccess(ExecutionStore executions, InMemoryExecutionOutputStore outputs) {
        this.executions = Objects.requireNonNull(executions, "executions must not be null");
        this.outputs = Objects.requireNonNull(outputs, "outputs must not be null");
    }

    @Override
    public ReadResult read(ReadRequest request) {
        ParsedReference parsed = parse(request.outputRef());
        var executionRequest =
                executions.findRequest(parsed.executionId()).orElseThrow(CliExecutionOutputAccess::notFound);
        var result = executions.findResult(parsed.executionId()).orElseThrow(CliExecutionOutputAccess::notFound);
        if (!executionRequest.context().tenant().equals(request.tenant())
                || !executionRequest.context().actor().equals(request.principal())
                || !executionRequest.context().runRef().equals(request.runId().value())) {
            throw notFound();
        }
        ExecutionOutput output = parsed.stderr() ? result.stderr() : result.stdout();
        AssetRef authoritative = output.optionalAssetRef().orElseThrow(CliExecutionOutputAccess::notFound);
        if (!authoritative.assetId().equals(request.outputRef())) throw notFound();
        if (output.binary()) {
            throw new AccessException(
                    FailureCode.EXECUTION_OUTPUT_BINARY_UNSUPPORTED,
                    "Binary execution output cannot be read through this text-only tool.");
        }
        byte[] bytes = outputs.load(authoritative)
                .orElseThrow(() -> new AccessException(
                        FailureCode.EXECUTION_OUTPUT_UNAVAILABLE,
                        "The execution output reference is valid, but retained bytes are unavailable."));
        if (output.byteCount() != bytes.length) {
            throw new AccessException(
                    FailureCode.EXECUTION_OUTPUT_UNAVAILABLE,
                    "The retained execution output is inconsistent with its authoritative metadata.");
        }
        return request.mode() == Mode.WINDOW ? window(request, output, bytes) : search(request, output, bytes);
    }

    private static ReadResult window(ReadRequest request, ExecutionOutput output, byte[] bytes) {
        if (request.maximumBytes() < 1 || request.maximumBytes() > 3072 || request.offsetBytes() < 0) {
            throw invalid("WINDOW limits are out of range");
        }
        if (request.offsetBytes() > bytes.length || request.offsetBytes() > Integer.MAX_VALUE) {
            throw invalid("offsetBytes exceeds retained output length");
        }
        int start = (int) request.offsetBytes();
        if (!utf8Boundary(bytes, start)) throw invalid("offsetBytes must be on a UTF-8 code point boundary");
        int end = (int) Math.min((long) bytes.length, (long) start + request.maximumBytes());
        while (end > start && end < bytes.length && continuation(bytes[end])) end--;
        if (end == start && start < bytes.length) {
            throw invalid("maximumBytes is too small for the next UTF-8 code point");
        }
        boolean hasMore = end < bytes.length;
        return new ReadResult(
                request.outputRef(),
                Mode.WINDOW,
                new String(bytes, start, end - start, StandardCharsets.UTF_8),
                start,
                end,
                hasMore ? (long) end : null,
                hasMore,
                bytes.length,
                output.truncated(),
                List.of());
    }

    private static ReadResult search(ReadRequest request, ExecutionOutput output, byte[] bytes) {
        byte[] needle = request.query().getBytes(StandardCharsets.UTF_8);
        if (request.query().isBlank()
                || request.query().length() > 256
                || request.maximumMatches() < 1
                || request.maximumMatches() > 20) {
            throw invalid("SEARCH arguments are out of range");
        }
        List<Match> matches = new ArrayList<>();
        int searchFrom = 0;
        int bodyBytes = 0;
        boolean hasMore = false;
        while (searchFrom <= bytes.length - needle.length) {
            int found = indexOf(bytes, needle, searchFrom);
            if (found < 0) break;
            if (matches.size() == request.maximumMatches()) {
                hasMore = true;
                break;
            }
            int remainingSlots = request.maximumMatches() - matches.size();
            int snippetBudget = Math.min(512, Math.max(32, (SEARCH_BODY_BUDGET_BYTES - bodyBytes) / remainingSlots));
            String snippet = snippet(bytes, found, needle.length, snippetBudget);
            int snippetBytes = snippet.getBytes(StandardCharsets.UTF_8).length;
            if (bodyBytes + snippetBytes > SEARCH_BODY_BUDGET_BYTES) {
                hasMore = true;
                break;
            }
            matches.add(new Match(found, snippet));
            bodyBytes += snippetBytes;
            searchFrom = found + Math.max(1, needle.length);
        }
        return new ReadResult(
                request.outputRef(), Mode.SEARCH, "", 0, 0, null, hasMore, bytes.length, output.truncated(), matches);
    }

    private static String snippet(byte[] bytes, int matchStart, int matchLength, int maximumBytes) {
        int center = matchStart + Math.min(matchLength, maximumBytes) / 2;
        int start = Math.max(0, center - maximumBytes / 2);
        int end = Math.min(bytes.length, start + maximumBytes);
        start = utf8Start(bytes, start);
        end = utf8End(bytes, start, end);
        String value = new String(bytes, start, Math.max(0, end - start), StandardCharsets.UTF_8);
        StringBuilder escaped = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (Character.isISOControl(codePoint)) escaped.append(String.format("\\u%04x", codePoint));
                    else escaped.appendCodePoint(codePoint);
                }
            }
        });
        return truncateUtf8(escaped.toString(), maximumBytes);
    }

    private static String truncateUtf8(String value, int maximumBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maximumBytes) return value;
        int end = maximumBytes;
        while (end > 0 && end < bytes.length && continuation(bytes[end])) end--;
        return new String(Arrays.copyOf(bytes, end), StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int index = from; index <= haystack.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) continue outer;
            }
            return index;
        }
        return -1;
    }

    private static ParsedReference parse(String outputRef) {
        if (!outputRef.startsWith(PREFIX)) throw notFound();
        boolean stderr;
        String suffix;
        if (outputRef.endsWith(":stdout")) {
            stderr = false;
            suffix = ":stdout";
        } else if (outputRef.endsWith(":stderr")) {
            stderr = true;
            suffix = ":stderr";
        } else {
            throw notFound();
        }
        String executionId = outputRef.substring(PREFIX.length(), outputRef.length() - suffix.length());
        if (executionId.isBlank()) throw notFound();
        return new ParsedReference(new ExecutionId(executionId), stderr);
    }

    private static int utf8Start(byte[] bytes, int offset) {
        while (offset < bytes.length && continuation(bytes[offset])) offset++;
        return offset;
    }

    private static int utf8End(byte[] bytes, int start, int end) {
        while (end > start && end < bytes.length && continuation(bytes[end])) end--;
        return end;
    }

    private static boolean utf8Boundary(byte[] bytes, int offset) {
        return offset == 0 || offset == bytes.length || !continuation(bytes[offset]);
    }

    private static boolean continuation(byte value) {
        return (value & 0xC0) == 0x80;
    }

    private static AccessException notFound() {
        return new AccessException(
                FailureCode.EXECUTION_OUTPUT_NOT_FOUND,
                "Execution output was not found in the current trusted Run scope.");
    }

    private static AccessException invalid(String message) {
        return new AccessException(FailureCode.INVALID_TOOL_ARGUMENT, message);
    }

    private record ParsedReference(ExecutionId executionId, boolean stderr) {}
}
