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

public final class AliyunSearchProvider implements WebSearchProvider {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://cloud-iqs.aliyuncs.com/search/unified");
    public static final CredentialRequirement CREDENTIAL =
            WebHttpSupport.credential("web-search-aliyun", "Aliyun IQS web search", "web.search");
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final WebProviderDescriptor descriptor;

    public AliyunSearchProvider() {
        this(defaultClient(), new ObjectMapper(), DEFAULT_ENDPOINT, Duration.ofSeconds(30), 2 * 1024 * 1024);
    }

    public AliyunSearchProvider(
            HttpClient client, ObjectMapper mapper, URI endpoint, Duration timeout, int maxResponseBytes) {
        this(client, mapper, endpoint, timeout, maxResponseBytes, Clock.systemUTC());
    }

    public AliyunSearchProvider(
            HttpClient client, ObjectMapper mapper, URI endpoint, Duration timeout, int maxResponseBytes, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.maxResponseBytes = positive(maxResponseBytes);
        this.descriptor = new WebProviderDescriptor(
                new WebProviderId("aliyun"),
                "Aliyun IQS Search",
                WebProviderCapabilities.searchOnly(Set.of(
                        WebSearchOption.FRESHNESS, WebSearchOption.INCLUDE_DOMAINS, WebSearchOption.EXCLUDE_DOMAINS)),
                "aliyun-iqs-unified-search",
                "1.0.0",
                endpoint,
                Set.of(endpoint.getHost().toLowerCase(java.util.Locale.ROOT)),
                Optional.of(CREDENTIAL),
                Map.of(
                        "engineType", "LiteAdvanced",
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
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", request.query());
            body.put("engineType", "LiteAdvanced");
            body.put(
                    "contents",
                    Map.of("mainText", false, "markdownText", false, "summary", false, "rerankScore", true));
            request.freshness().ifPresent(value -> body.put("timeRange", freshness(value)));
            Map<String, Object> advancedParameters = new LinkedHashMap<>();
            advancedParameters.put("numResults", Integer.toString(request.maxResults()));
            if (!request.includeDomains().isEmpty()) {
                advancedParameters.put("includeSites", String.join(",", request.includeDomains()));
            }
            if (!request.excludeDomains().isEmpty()) {
                advancedParameters.put("excludeSites", String.join(",", request.excludeDomains()));
            }
            body.put("advancedParams", advancedParameters);
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
            var providerError = WebHttpSupport.providerError(root);
            if (providerError != null) throw providerError;
            JsonNode items = root.path("pageItems");
            if (!items.isArray()) {
                throw WebHttpSupport.failure(
                        io.haifa.agent.application.project.tool.web.WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                        io.haifa.agent.application.project.tool.web.WebDispatchState.ACKNOWLEDGED,
                        "Aliyun search response did not contain pageItems");
            }
            List<WebSearchResult> results = new ArrayList<>();
            boolean truncated = items.size() > request.maxResults();
            for (JsonNode item : items) {
                if (results.size() >= request.maxResults()) break;
                String title = WebHttpSupport.bounded(item.path("title").asText(""), 1024);
                String link = item.path("link").asText("").trim();
                if (title.isBlank() || link.isBlank()) {
                    throw WebHttpSupport.failure(
                            io.haifa.agent.application.project.tool.web.WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                            io.haifa.agent.application.project.tool.web.WebDispatchState.ACKNOWLEDGED,
                            "Aliyun search returned an invalid result");
                }
                String snippet = WebHttpSupport.bounded(item.path("snippet").asText(""), 8192);
                Optional<Double> score =
                        item.has("rerankScore") && item.path("rerankScore").isNumber()
                                ? Optional.of(item.path("rerankScore").doubleValue())
                                : Optional.empty();
                results.add(new WebSearchResult(
                        results.size() + 1, title, WebHttpSupport.absoluteUri(link), snippet, Optional.empty(), score));
            }
            return new WebSearchResponse(request.query(), results, optionalRequestId(root), truncated);
        });
    }

    private static Optional<String> optionalRequestId(JsonNode root) {
        String value = root.path("requestId").asText("").trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(WebHttpSupport.bounded(value, 256));
    }

    private static String freshness(WebFreshness freshness) {
        return switch (freshness) {
            case DAY -> "OneDay";
            case WEEK -> "OneWeek";
            case MONTH -> "OneMonth";
            case YEAR -> "OneYear";
        };
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static int positive(int value) {
        if (value < 1024) throw new IllegalArgumentException("maxResponseBytes must be at least 1024");
        return value;
    }
}
