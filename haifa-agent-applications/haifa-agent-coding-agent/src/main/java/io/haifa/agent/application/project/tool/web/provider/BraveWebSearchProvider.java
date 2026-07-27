package io.haifa.agent.application.project.tool.web.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.tool.web.WebFreshness;
import io.haifa.agent.application.project.tool.web.WebProviderCapabilities;
import io.haifa.agent.application.project.tool.web.WebProviderDescriptor;
import io.haifa.agent.application.project.tool.web.WebProviderId;
import io.haifa.agent.application.project.tool.web.WebProviderInvocationContext;
import io.haifa.agent.application.project.tool.web.WebSafeSearch;
import io.haifa.agent.application.project.tool.web.WebSearchOption;
import io.haifa.agent.application.project.tool.web.WebSearchProvider;
import io.haifa.agent.application.project.tool.web.WebSearchRequest;
import io.haifa.agent.application.project.tool.web.WebSearchResponse;
import io.haifa.agent.application.project.tool.web.WebSearchResult;
import io.haifa.agent.credential.api.CredentialRequirement;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class BraveWebSearchProvider implements WebSearchProvider {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://api.search.brave.com/res/v1/web/search");
    public static final CredentialRequirement CREDENTIAL =
            WebHttpSupport.credential("web-search-brave", "Brave web search", "web.search");
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final WebProviderDescriptor descriptor;

    public BraveWebSearchProvider() {
        this(defaultClient(), new ObjectMapper(), DEFAULT_ENDPOINT, Duration.ofSeconds(20), 2 * 1024 * 1024);
    }

    public BraveWebSearchProvider(
            HttpClient client, ObjectMapper mapper, URI endpoint, Duration timeout, int maxResponseBytes) {
        this(client, mapper, endpoint, timeout, maxResponseBytes, Clock.systemUTC());
    }

    public BraveWebSearchProvider(
            HttpClient client, ObjectMapper mapper, URI endpoint, Duration timeout, int maxResponseBytes, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (maxResponseBytes < 1024) throw new IllegalArgumentException("maxResponseBytes must be at least 1024");
        this.maxResponseBytes = maxResponseBytes;
        this.descriptor = new WebProviderDescriptor(
                new WebProviderId("brave"),
                "Brave Search",
                WebProviderCapabilities.searchOnly(Set.of(
                        WebSearchOption.LANGUAGE,
                        WebSearchOption.COUNTRY,
                        WebSearchOption.FRESHNESS,
                        WebSearchOption.SAFE_SEARCH)),
                "brave-web-search",
                "1.0.0",
                endpoint,
                Set.of(endpoint.getHost().toLowerCase(java.util.Locale.ROOT)),
                Optional.of(CREDENTIAL),
                Map.of(
                        "maxResponseBytes", Integer.toString(maxResponseBytes),
                        "timeoutMillis", Long.toString(timeout.toMillis())));
    }

    @Override
    public WebProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request, WebProviderInvocationContext context) {
        return WebHttpSupport.withCredential(context, clock, key -> {
            Map<String, String> parameters = WebHttpSupport.parameters();
            parameters.put("q", request.query());
            parameters.put("count", Integer.toString(request.maxResults()));
            request.country().ifPresent(value -> parameters.put("country", value));
            request.language().ifPresent(value -> parameters.put("search_lang", value));
            request.freshness().ifPresent(value -> parameters.put("freshness", freshness(value)));
            request.safeSearch().ifPresent(value -> parameters.put("safesearch", safeSearch(value)));
            JsonNode root = WebHttpSupport.getJson(
                    client,
                    mapper,
                    WebHttpSupport.withQuery(descriptor.endpoint(), parameters),
                    Map.of("X-Subscription-Token", key),
                    timeout,
                    maxResponseBytes,
                    context,
                    clock);
            JsonNode items = root.path("web").path("results");
            if (!items.isArray()) {
                throw WebHttpSupport.failure(
                        io.haifa.agent.application.project.tool.web.WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                        io.haifa.agent.application.project.tool.web.WebDispatchState.ACKNOWLEDGED,
                        "Brave response did not contain web results");
            }
            List<WebSearchResult> results = new ArrayList<>();
            boolean truncated = items.size() > request.maxResults();
            for (JsonNode item : items) {
                if (results.size() >= request.maxResults()) break;
                String title = WebHttpSupport.bounded(item.path("title").asText(""), 1024);
                String url = item.path("url").asText("").trim();
                if (title.isBlank() || url.isBlank()) {
                    throw WebHttpSupport.failure(
                            io.haifa.agent.application.project.tool.web.WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                            io.haifa.agent.application.project.tool.web.WebDispatchState.ACKNOWLEDGED,
                            "Brave returned an invalid result");
                }
                results.add(new WebSearchResult(
                        results.size() + 1,
                        title,
                        WebHttpSupport.absoluteUri(url),
                        WebHttpSupport.bounded(item.path("description").asText(""), 8192),
                        Optional.empty(),
                        Optional.empty()));
            }
            String query = root.path("query").path("original").asText(request.query());
            return new WebSearchResponse(query, results, Optional.empty(), truncated);
        });
    }

    private static String freshness(WebFreshness value) {
        return switch (value) {
            case DAY -> "pd";
            case WEEK -> "pw";
            case MONTH -> "pm";
            case YEAR -> "py";
        };
    }

    private static String safeSearch(WebSafeSearch value) {
        return switch (value) {
            case OFF -> "off";
            case MODERATE -> "moderate";
            case STRICT -> "strict";
        };
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
