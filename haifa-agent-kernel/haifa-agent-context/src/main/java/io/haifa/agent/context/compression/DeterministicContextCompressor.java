package io.haifa.agent.context.compression;

import io.haifa.agent.context.budget.HeuristicTokenEstimator;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.tool.ToolCallId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Local deterministic compressor used until a separately governed summarization model is configured. */
public final class DeterministicContextCompressor implements ContextCompressor {
    @Override
    public CompressionResult compress(CompressionRequest request) {
        if (request.sourceMessages().stream()
                .anyMatch(message -> message.visibility() == MessageVisibility.INTERNAL
                        || message.visibility() == MessageVisibility.ADMIN_VISIBLE
                        || message.visibility() == MessageVisibility.HIDDEN
                        || message.visibility() == MessageVisibility.REDACTED)) {
            throw new IllegalArgumentException("compression source contains content not authorized for model context");
        }
        List<String> facts = new ArrayList<>();
        Set<ToolCallId> tools = new LinkedHashSet<>();
        Set<String> labels = new LinkedHashSet<>();
        for (var message : request.sourceMessages()) {
            labels.add(message.visibility().name().toLowerCase());
            for (var part : message.contents()) {
                if (part instanceof TextPart text && facts.size() < request.maxFacts()) {
                    String normalized = text.text().replaceAll("\\s+", " ").trim();
                    if (!normalized.isEmpty()) {
                        facts.add(message.role().name() + ": " + bounded(normalized, 240));
                    }
                } else if (part instanceof ToolResultPart result) {
                    tools.add(result.toolCallId());
                    if (facts.size() < request.maxFacts()) {
                        facts.add("TOOL: " + bounded(result.summary(), 240));
                    }
                }
            }
        }
        if (facts.isEmpty() && tools.isEmpty()) {
            throw new IllegalArgumentException("compression result would be empty");
        }
        var first = request.sourceMessages().getFirst();
        var last = request.sourceMessages().getLast();
        String sourceHash = hash(request.sourceMessages().stream()
                .map(message -> message.id().value() + "@" + message.sequence())
                .toList()
                .toString());
        int estimated = HeuristicTokenEstimator.tokens(String.join("\n", facts)) + tools.size() * 8;
        return new CompressionResult(new ConversationSummary(
                request.summaryId(),
                request.version(),
                request.sessionId(),
                first.cursor(),
                last.cursor(),
                request.sourceMessages().stream().map(message -> message.id()).toList(),
                sourceHash,
                facts,
                List.of(),
                List.of(),
                List.copyOf(tools),
                Math.max(1, estimated),
                request.createdAt(),
                request.policyVersion(),
                version(),
                Set.copyOf(labels),
                true));
    }

    @Override
    public String version() {
        return "deterministic-session-v1";
    }

    private static String bounded(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String hash(String value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
