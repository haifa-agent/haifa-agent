package io.haifa.agent.application.project.tool.web.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.tool.web.WebFreshness;
import io.haifa.agent.application.project.tool.web.WebProviderCapabilities;
import io.haifa.agent.application.project.tool.web.WebProviderDescriptor;
import io.haifa.agent.application.project.tool.web.WebProviderId;
import io.haifa.agent.application.project.tool.web.WebProviderInvocationContext;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class TavilyWebSearchProvider implements WebSearchProvider {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://api.tavily.com/search");
    public static final CredentialRequirement CREDENTIAL =
            WebHttpSupport.credential("web-search-tavily", "Tavily web search", "web.search");
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final WebProviderDescriptor descriptor;

    public TavilyWebSearchProvider() {
        this(defaultClient(), new ObjectMapper(), DEFAULT_ENDPOINT, Duration.ofSeconds(20), 2 * 1024 * 1024);
    }

    public TavilyWebSearchProvider(
            HttpClient client, ObjectMapper mapper, URI endpoint, Duration timeout, int maxResponseBytes) {
        this(client, mapper, endpoint, timeout, maxResponseBytes, Clock.systemUTC());
    }

    public TavilyWebSearchProvider(
            HttpClient client, ObjectMapper mapper, URI endpoint, Duration timeout, int maxResponseBytes, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (maxResponseBytes < 1024) throw new IllegalArgumentException("maxResponseBytes must be at least 1024");
        this.maxResponseBytes = maxResponseBytes;
        this.descriptor = new WebProviderDescriptor(
                new WebProviderId("tavily"),
                "Tavily Search",
                WebProviderCapabilities.searchOnly(Set.of(
                        WebSearchOption.COUNTRY,
                        WebSearchOption.FRESHNESS,
                        WebSearchOption.INCLUDE_DOMAINS,
                        WebSearchOption.EXCLUDE_DOMAINS)),
                "tavily-search",
                "1.0.0",
                endpoint,
                Set.of(endpoint.getHost().toLowerCase(java.util.Locale.ROOT)),
                Optional.of(CREDENTIAL),
                Map.of(
                        "autoParameters", "false",
                        "includeAnswer", "false",
                        "includeImages", "false",
                        "includeRawContent", "false",
                        "maxResponseBytes", Integer.toString(maxResponseBytes),
                        "searchDepth", "basic",
                        "timeoutMillis", Long.toString(timeout.toMillis())));
    }

    @Override
    public WebProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request, WebProviderInvocationContext context) {
        return WebHttpSupport.withCredential(context, clock, key -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", request.query());
            body.put("max_results", request.maxResults());
            body.put("search_depth", "basic");
            body.put("topic", "general");
            body.put("include_answer", false);
            body.put("include_raw_content", false);
            body.put("include_images", false);
            body.put("auto_parameters", false);
            request.country().ifPresent(value -> body.put("country", value));
            request.freshness().ifPresent(value -> body.put("time_range", freshness(value)));
            if (!request.includeDomains().isEmpty()) body.put("include_domains", request.includeDomains());
            if (!request.excludeDomains().isEmpty()) body.put("exclude_domains", request.excludeDomains());
            JsonNode root = WebHttpSupport.postJson(
                    client,
                    mapper,
                    descriptor.endpoint(),
                    body,
                    Map.of("Authorization", "Bearer " + key),
                    timeout,
                    maxResponseBytes,
                    context,
                    clock);
            JsonNode items = root.path("results");
            if (!items.isArray()) {
                throw WebHttpSupport.failure(
                        io.haifa.agent.application.project.tool.web.WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                        io.haifa.agent.application.project.tool.web.WebDispatchState.ACKNOWLEDGED,
                        "Tavily response did not contain results");
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
                            "Tavily returned an invalid result");
                }
                Optional<Double> score = item.path("score").isNumber()
                        ? Optional.of(item.path("score").doubleValue())
                        : Optional.empty();
                results.add(new WebSearchResult(
                        results.size() + 1,
                        title,
                        WebHttpSupport.absoluteUri(url),
                        WebHttpSupport.bounded(item.path("content").asText(""), 8192),
                        Optional.empty(),
                        score));
            }
            String normalizedQuery = root.path("query").asText(request.query());
            String requestId = root.path("request_id").asText("").trim();
            return new WebSearchResponse(
                    normalizedQuery,
                    results,
                    requestId.isBlank() ? Optional.empty() : Optional.of(WebHttpSupport.bounded(requestId, 256)),
                    truncated);
        });
    }

    private static String freshness(WebFreshness value) {
        return switch (value) {
            case DAY -> "day";
            case WEEK -> "week";
            case MONTH -> "month";
            case YEAR -> "year";
        };
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
