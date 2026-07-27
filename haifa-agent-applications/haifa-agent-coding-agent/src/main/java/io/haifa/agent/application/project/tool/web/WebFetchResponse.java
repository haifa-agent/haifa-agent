package io.haifa.agent.application.project.tool.web;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

public record WebFetchResponse(
        URI requestedUrl,
        URI finalUrl,
        Optional<String> title,
        String content,
        WebContentFormat format,
        String mediaType,
        Optional<String> charset,
        String contentSha256,
        boolean truncated) {
    public WebFetchResponse {
        requestedUrl = Objects.requireNonNull(requestedUrl, "requestedUrl").normalize();
        finalUrl = Objects.requireNonNull(finalUrl, "finalUrl").normalize();
        title = WebValues.optionalText(title, "title", 1024);
        content = Objects.requireNonNull(content, "content");
        if (content.isBlank()) throw new IllegalArgumentException("content must not be blank");
        Objects.requireNonNull(format, "format");
        mediaType = WebValues.text(mediaType, "mediaType", 128).toLowerCase(java.util.Locale.ROOT);
        charset = WebValues.optionalText(charset, "charset", 64);
        contentSha256 = WebValues.text(contentSha256, "contentSha256", 64).toLowerCase(java.util.Locale.ROOT);
        if (!contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentSha256 must be lowercase SHA-256");
        }
    }
}
