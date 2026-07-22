package io.haifa.agent.mcp.tool;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.credential.api.SecretRedactor;
import io.haifa.agent.mcp.protocol.McpRemoteContent;
import io.haifa.agent.mcp.protocol.McpRemoteToolResult;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class McpContentMapper {
    private static final int MAX_SUMMARY_CHARS = 16_384;
    private static final int MAX_MEDIA_BYTES = 1024 * 1024;
    private final SecretRedactor redactor;
    private final Optional<McpContentExternalizer> externalizer;

    public McpContentMapper(SecretRedactor redactor) {
        this(redactor, null);
    }

    public McpContentMapper(SecretRedactor redactor, McpContentExternalizer externalizer) {
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.externalizer = Optional.ofNullable(externalizer);
    }

    public ToolResult map(McpRemoteToolResult result) {
        StringBuilder summary = new StringBuilder();
        var assets = new java.util.ArrayList<io.haifa.agent.core.reference.AssetRef>();
        for (McpRemoteContent content : result.content()) {
            if (content.kind() == McpRemoteContent.Kind.TEXT) {
                if (!summary.isEmpty()) summary.append('\n');
                summary.append(content.text());
            } else {
                if (content.encodedBytes() > MAX_MEDIA_BYTES) {
                    throw new ToolInvocationException(
                            "MCP_CONTENT_OVERSIZE",
                            ToolDispatchState.ACKNOWLEDGED,
                            "MCP result media exceeds the safe externalization limit");
                }
                if ((content.kind() == McpRemoteContent.Kind.IMAGE || content.kind() == McpRemoteContent.Kind.AUDIO)
                        && externalizer.isPresent()) {
                    assets.add(externalizer.orElseThrow().externalize(content));
                } else {
                    throw new ToolInvocationException(
                            "MCP_CONTENT_UNSUPPORTED",
                            ToolDispatchState.ACKNOWLEDGED,
                            "MCP result content requires an Asset adapter that is not configured");
                }
            }
        }
        String text = summary.isEmpty()
                ? (result.error() ? "remote MCP tool failed" : "remote MCP tool completed")
                : summary.toString();
        boolean truncated = text.length() > MAX_SUMMARY_CHARS;
        if (truncated) text = text.substring(0, MAX_SUMMARY_CHARS);
        text = redactor.redact(text);
        Map<String, Object> structured = redact(result.structuredContent());
        return new ToolResult(!result.error(), text, structured, assets, List.of(), truncated);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> redact(Map<String, Object> values) {
        var output = new java.util.LinkedHashMap<String, Object>();
        values.forEach((key, value) -> output.put(redactor.redact(key), redactValue(value)));
        return output;
    }

    private Object redactValue(Object value) {
        if (value instanceof String text) return redactor.redact(text);
        if (value instanceof Map<?, ?> map) return redact((Map<String, Object>) map);
        if (value instanceof List<?> list)
            return list.stream().map(this::redactValue).toList();
        return value;
    }
}
