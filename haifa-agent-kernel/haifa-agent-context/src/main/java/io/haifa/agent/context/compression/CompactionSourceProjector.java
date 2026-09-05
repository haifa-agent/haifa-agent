package io.haifa.agent.context.compression;

import io.haifa.agent.core.content.ArtifactRefPart;
import io.haifa.agent.core.content.AssetRefPart;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.tool.ToolCallId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure Java projector that filters, sanitizes, and aliases authoritative session messages
 * into a safe, bounded text stream for semantic summarization.
 */
public final class CompactionSourceProjector {

    private static final int MAX_TEXT_CHARS = 2048;
    private static final int MAX_TOOL_SUMMARY_CHARS = 512;

    private static final Pattern THINKING_TAGS = Pattern.compile("(?s)<think>.*?</think>|<thinking>.*?</thinking>");
    private static final Pattern API_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9_-]{16,}");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]{16,}");
    private static final Pattern CONTINUATION_PATTERN = Pattern.compile("(?i)PROTECTED_CONTINUATION(:[A-Za-z0-9_-]+)?");

    private CompactionSourceProjector() {}

    public static ProjectedCompactionSource project(List<AgentMessage> sourceMessages) {
        Objects.requireNonNull(sourceMessages, "sourceMessages must not be null");

        Map<String, AgentMessageId> messageAliases = new LinkedHashMap<>();
        Map<String, ToolCallId> toolAliases = new LinkedHashMap<>();
        List<AgentMessageId> includedMessageIds = new ArrayList<>();
        List<ToolCallId> toolOutcomeReferences = new ArrayList<>();
        Set<String> securityLabels = new LinkedHashSet<>();
        StringBuilder textBuilder = new StringBuilder();

        int messageIndex = 1;
        int toolIndex = 1;

        for (AgentMessage message : sourceMessages) {
            if (message.status() != MessageStatus.COMPLETED) {
                continue;
            }
            if (message.visibility() != MessageVisibility.USER_VISIBLE
                    && message.visibility() != MessageVisibility.AGENT_VISIBLE) {
                continue;
            }

            String messageAlias = String.format(Locale.ROOT, "m%03d", messageIndex++);
            messageAliases.put(messageAlias, message.id());
            includedMessageIds.add(message.id());
            securityLabels.add(message.visibility().name().toLowerCase(Locale.ROOT));

            String roleName = message.role().name().toLowerCase(Locale.ROOT);
            List<String> contentLines = new ArrayList<>();

            for (ContentPart part : message.contents()) {
                if (part instanceof TextPart textPart) {
                    String sanitized = sanitize(textPart.text());
                    if (!sanitized.isBlank()) {
                        contentLines.add(bounded(sanitized, MAX_TEXT_CHARS));
                    }
                } else if (part instanceof ToolCallPart callPart) {
                    String toolAlias = String.format(Locale.ROOT, "t%03d", toolIndex++);
                    toolAliases.put(toolAlias, callPart.toolCallId());
                    contentLines.add(String.format(Locale.ROOT, "[tool-call %s: %s]", toolAlias, callPart.toolName()));
                } else if (part instanceof ToolResultPart resultPart) {
                    String toolAlias = null;
                    for (Map.Entry<String, ToolCallId> entry : toolAliases.entrySet()) {
                        if (entry.getValue().equals(resultPart.toolCallId())) {
                            toolAlias = entry.getKey();
                            break;
                        }
                    }
                    if (toolAlias == null) {
                        toolAlias = String.format(Locale.ROOT, "t%03d", toolIndex++);
                        toolAliases.put(toolAlias, resultPart.toolCallId());
                    }
                    toolOutcomeReferences.add(resultPart.toolCallId());
                    String sanitizedSummary = sanitize(resultPart.summary());
                    contentLines.add(String.format(
                            Locale.ROOT,
                            "[tool-outcome %s: %s]",
                            toolAlias,
                            bounded(sanitizedSummary, MAX_TOOL_SUMMARY_CHARS)));
                } else if (part instanceof AssetRefPart assetPart) {
                    contentLines.add(String.format(
                            Locale.ROOT, "[asset-ref: %s]", assetPart.asset().assetId()));
                } else if (part instanceof ArtifactRefPart artifactPart) {
                    contentLines.add(String.format(
                            Locale.ROOT,
                            "[artifact-ref: %s]",
                            artifactPart.artifact().artifactId()));
                }
            }

            if (!contentLines.isEmpty()) {
                if (!textBuilder.isEmpty()) {
                    textBuilder.append("\n");
                }
                textBuilder.append(String.format(Locale.ROOT, "[%s %s completed] ", messageAlias, roleName));
                textBuilder.append(String.join(" ", contentLines));
            }
        }

        return new ProjectedCompactionSource(
                textBuilder.toString(),
                messageAliases,
                toolAliases,
                includedMessageIds,
                toolOutcomeReferences,
                securityLabels);
    }

    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String stripped = THINKING_TAGS.matcher(raw).replaceAll(" ");
        stripped = CONTINUATION_PATTERN.matcher(stripped).replaceAll("[REDACTED_CONTINUATION]");
        stripped = API_KEY_PATTERN.matcher(stripped).replaceAll("[REDACTED_KEY]");
        stripped = BEARER_PATTERN.matcher(stripped).replaceAll("Bearer [REDACTED]");
        return stripped.replaceAll("\\s+", " ").trim();
    }

    private static String bounded(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "…";
    }
}
