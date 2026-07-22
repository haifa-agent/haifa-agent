package io.haifa.agent.mcp.protocol;

import java.util.Objects;

public record McpRemoteContent(Kind kind, String text, String mimeType, int encodedBytes) {
    public enum Kind {
        TEXT,
        IMAGE,
        AUDIO,
        EMBEDDED_RESOURCE,
        RESOURCE_LINK,
        UNSUPPORTED
    }

    public McpRemoteContent {
        Objects.requireNonNull(kind, "kind");
        text = text == null ? "" : text;
        mimeType = mimeType == null ? "" : mimeType;
        if (encodedBytes < 0) throw new IllegalArgumentException("encodedBytes must not be negative");
    }
}
