package io.haifa.agent.web.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.web.WebContentFormat;
import io.haifa.agent.web.WebDispatchState;
import io.haifa.agent.web.WebFailureCode;
import io.haifa.agent.web.WebFetchProvider;
import io.haifa.agent.web.WebFetchRequest;
import io.haifa.agent.web.WebFetchResponse;
import io.haifa.agent.web.WebProviderCapabilities;
import io.haifa.agent.web.WebProviderDescriptor;
import io.haifa.agent.web.WebProviderId;
import io.haifa.agent.web.WebProviderInvocationContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Tavily Extract API adapter for cleaned Markdown or text page content. */
public final class TavilyFetchProvider implements WebFetchProvider {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://api.tavily.com/extract");
    public static final CredentialRequirement CREDENTIAL =
            WebHttpSupport.credential("web-fetch-tavily", "Tavily web fetch", "web.fetch");

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final WebProviderDescriptor descriptor;

    public TavilyFetchProvider() {
        this(defaultClient(), new ObjectMapper(), DEFAULT_ENDPOINT, Duration.ofSeconds(30), 4 * 1024 * 1024);
    }

    public TavilyFetchProvider(
            HttpClient client, ObjectMapper mapper, URI endpoint, Duration timeout, int maxResponseBytes) {
        this(client, mapper, endpoint, timeout, maxResponseBytes, Clock.systemUTC());
    }

    public TavilyFetchProvider(
            HttpClient client, ObjectMapper mapper, URI endpoint, Duration timeout, int maxResponseBytes, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        requireEndpoint(endpoint);
        if (maxResponseBytes < 1024) throw new IllegalArgumentException("maxResponseBytes must be at least 1024");
        this.maxResponseBytes = maxResponseBytes;
        this.descriptor = new WebProviderDescriptor(
                new WebProviderId("tavily"),
                "Tavily Extract",
                WebProviderCapabilities.fetchOnly(),
                "tavily-extract",
                "1.0.0",
                endpoint,
                Set.of(endpoint.getHost().toLowerCase(java.util.Locale.ROOT)),
                Optional.of(CREDENTIAL),
                Map.of(
                        "extractDepth",
                        "basic",
                        "includeImages",
                        "false",
                        "maxResponseBytes",
                        Integer.toString(maxResponseBytes),
                        "timeoutMillis",
                        Long.toString(timeout.toMillis())));
    }

    @Override
    public WebProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WebFetchResponse fetch(WebFetchRequest request, WebProviderInvocationContext context) {
        return WebHttpSupport.withCredential(context, clock, key -> {
            SelectedFormat selected = selectedFormat(request.preferredFormat());
            JsonNode root = WebHttpSupport.postJson(
                    client,
                    mapper,
                    descriptor.endpoint(),
                    Map.of(
                            "urls",
                            List.of(request.url().toString()),
                            "extract_depth",
                            "basic",
                            "format",
                            selected.requestValue(),
                            "include_images",
                            false,
                            "include_favicon",
                            false,
                            "include_usage",
                            false),
                    Map.of("Authorization", "Bearer " + key),
                    timeout,
                    maxResponseBytes,
                    context,
                    clock);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                throw WebHttpSupport.failure(
                        WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                        WebDispatchState.ACKNOWLEDGED,
                        "Tavily extract response did not contain results");
            }
            if (results.isEmpty()) {
                throw WebHttpSupport.failure(
                        WebFailureCode.WEB_PROVIDER_FAILED,
                        WebDispatchState.ACKNOWLEDGED,
                        "Tavily could not extract the requested page");
            }
            JsonNode result = results.get(0);
            String complete = result.path("raw_content").asText("").trim();
            if (complete.isBlank()) {
                throw WebHttpSupport.failure(
                        WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                        WebDispatchState.ACKNOWLEDGED,
                        "Tavily extract response did not contain page content");
            }
            boolean truncated = complete.length() > request.maxCharacters();
            String content = truncated ? complete.substring(0, request.maxCharacters()) : complete;
            URI finalUrl = result.hasNonNull("url")
                    ? WebHttpSupport.absoluteUri(result.path("url").asText())
                    : request.url();
            return new WebFetchResponse(
                    request.url(),
                    finalUrl,
                    Optional.empty(),
                    content,
                    selected.format(),
                    selected.mediaType(),
                    Optional.of("UTF-8"),
                    WebHttpSupport.sha256(content),
                    truncated);
        });
    }

    private static SelectedFormat selectedFormat(WebContentFormat preferred) {
        return preferred == WebContentFormat.TEXT
                ? new SelectedFormat("text", WebContentFormat.TEXT, "text/plain")
                : new SelectedFormat("markdown", WebContentFormat.MARKDOWN, "text/markdown");
    }

    private static void requireEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || !(endpoint.getScheme().equalsIgnoreCase("https")
                        || endpoint.getScheme().equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("Tavily endpoint must be an absolute HTTP(S) URI");
        }
        if (endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Tavily endpoint must not contain user info, query, or fragment");
        }
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private record SelectedFormat(String requestValue, WebContentFormat format, String mediaType) {}
}
