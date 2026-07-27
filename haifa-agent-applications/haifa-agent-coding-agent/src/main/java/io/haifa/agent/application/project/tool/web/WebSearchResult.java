package io.haifa.agent.application.project.tool.web;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record WebSearchResult(
        int rank, String title, URI url, String snippet, Optional<Instant> publishedAt, Optional<Double> score) {
    public WebSearchResult {
        if (rank < 1) throw new IllegalArgumentException("rank must be positive");
        title = WebValues.text(title, "title", 1024);
        url = Objects.requireNonNull(url, "url").normalize();
        if (!url.isAbsolute()
                || url.getHost() == null
                || url.getRawUserInfo() != null
                || url.toString().length() > 4096
                || !(url.getScheme().equalsIgnoreCase("http") || url.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("url must be an absolute HTTP(S) URL");
        }
        snippet = Objects.requireNonNull(snippet, "snippet").trim();
        if (snippet.length() > 8192) throw new IllegalArgumentException("snippet is too long");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
        score = Objects.requireNonNull(score, "score");
        score.ifPresent(value -> {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("score must be finite");
        });
    }
}
