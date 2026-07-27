package io.haifa.agent.application.project.tool.web;

import java.util.Objects;
import java.util.Set;

public record WebProviderCapabilities(boolean search, boolean fetch, Set<WebSearchOption> supportedSearchOptions) {
    public WebProviderCapabilities {
        supportedSearchOptions = Set.copyOf(Objects.requireNonNull(supportedSearchOptions, "supportedSearchOptions"));
        if (!search && !fetch) throw new IllegalArgumentException("provider must support search or fetch");
        if (!search && !supportedSearchOptions.isEmpty()) {
            throw new IllegalArgumentException("fetch-only provider cannot declare search options");
        }
    }

    public static WebProviderCapabilities searchOnly(Set<WebSearchOption> options) {
        return new WebProviderCapabilities(true, false, options);
    }

    public static WebProviderCapabilities fetchOnly() {
        return new WebProviderCapabilities(false, true, Set.of());
    }
}
