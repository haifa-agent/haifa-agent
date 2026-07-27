package io.haifa.agent.application.project.tool.web;

import java.net.URI;
import java.util.Objects;

public record WebFetchRequest(URI url, WebContentFormat preferredFormat, int maxCharacters) {
    public WebFetchRequest {
        url = Objects.requireNonNull(url, "url").normalize();
        Objects.requireNonNull(preferredFormat, "preferredFormat");
        if (preferredFormat == WebContentFormat.HTML) {
            throw new IllegalArgumentException("HTML cannot be requested as a preferred format");
        }
        if (maxCharacters < 1 || maxCharacters > 1_000_000) {
            throw new IllegalArgumentException("maxCharacters must be between 1 and 1000000");
        }
    }
}
