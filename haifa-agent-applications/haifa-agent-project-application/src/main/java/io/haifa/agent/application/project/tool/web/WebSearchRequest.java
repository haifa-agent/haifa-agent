package io.haifa.agent.application.project.tool.web;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record WebSearchRequest(
        String query,
        int maxResults,
        Optional<String> language,
        Optional<String> country,
        Optional<WebFreshness> freshness,
        List<String> includeDomains,
        List<String> excludeDomains,
        Optional<WebSafeSearch> safeSearch) {
    public WebSearchRequest {
        query = WebValues.text(query, "query", 2048);
        if (maxResults < 1 || maxResults > 20) {
            throw new IllegalArgumentException("maxResults must be between 1 and 20");
        }
        language = WebValues.optionalText(language, "language", 32);
        country = WebValues.optionalText(country, "country", 64);
        freshness = Objects.requireNonNull(freshness, "freshness");
        includeDomains = WebValues.domains(includeDomains, "includeDomains");
        excludeDomains = WebValues.domains(excludeDomains, "excludeDomains");
        if (includeDomains.stream().anyMatch(excludeDomains::contains)) {
            throw new IllegalArgumentException("a domain cannot be both included and excluded");
        }
        safeSearch = Objects.requireNonNull(safeSearch, "safeSearch");
    }
}
