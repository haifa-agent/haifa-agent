package io.haifa.agent.application.project.tool.web;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WebSearchToolProvider implements ToolProvider {
    private final WebSearchProvider provider;
    private final ToolProviderId id;

    public WebSearchToolProvider(WebSearchProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
        if (!provider.descriptor().capabilities().search()) {
            throw new IllegalArgumentException("provider does not support search");
        }
        this.id = new ToolProviderId("web-search." + provider.descriptor().id().value());
    }

    @Override
    public ToolProviderId id() {
        return id;
    }

    @Override
    public ToolResult invoke(ToolInvocationRequest request) {
        if (!request.binding().definition().name().value().equals("web.search")) {
            throw WebToolProviderSupport.invalid("web search provider received a different tool");
        }
        var webRequest =
                WebToolProviderSupport.searchRequest(request.arguments().values());
        WebToolProviderSupport.requireSupported(
                webRequest, provider.descriptor().capabilities().supportedSearchOptions());
        try {
            var response = provider.search(webRequest, WebToolProviderSupport.context(request));
            List<Map<String, Object>> results = new ArrayList<>();
            response.results().forEach(result -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("rank", result.rank());
                value.put("title", result.title());
                value.put("url", result.url().toString());
                value.put("snippet", result.snippet());
                result.publishedAt().ifPresent(item -> value.put("publishedAt", item.toString()));
                result.score().ifPresent(item -> value.put("score", item));
                results.add(Map.copyOf(value));
            });
            Map<String, Object> data = Map.of(
                    "query", response.normalizedQuery(),
                    "results", List.copyOf(results),
                    "truncated", response.truncated(),
                    "untrustedExternalContent", true);
            return new ToolResult(
                    true,
                    "Web search returned " + results.size() + " untrusted external result(s).",
                    data,
                    List.of(),
                    List.of(),
                    response.truncated());
        } catch (WebProviderException exception) {
            throw WebToolProviderSupport.map(exception);
        }
    }
}
