package io.haifa.agent.application.project.tool.web;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record WebSearchResponse(
        String normalizedQuery, List<WebSearchResult> results, Optional<String> providerRequestId, boolean truncated) {
    public WebSearchResponse {
        normalizedQuery = WebValues.text(normalizedQuery, "normalizedQuery", 2048);
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        if (results.size() > 20) throw new IllegalArgumentException("too many search results");
        var ranks = new HashSet<Integer>();
        int totalCharacters = 0;
        for (WebSearchResult result : results) {
            if (!ranks.add(result.rank())) throw new IllegalArgumentException("duplicate search result rank");
            totalCharacters += result.title().length()
                    + result.url().toString().length()
                    + result.snippet().length();
        }
        if (totalCharacters > 200_000) throw new IllegalArgumentException("search response is too large");
        providerRequestId = WebValues.optionalText(providerRequestId, "providerRequestId", 256);
    }
}
