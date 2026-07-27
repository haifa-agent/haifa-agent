package io.haifa.agent.application.project.tool.web.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.tool.web.WebContentFormat;
import io.haifa.agent.application.project.tool.web.WebDispatchState;
import io.haifa.agent.application.project.tool.web.WebFailureCode;
import io.haifa.agent.application.project.tool.web.WebFetchProvider;
import io.haifa.agent.application.project.tool.web.WebFetchRequest;
import io.haifa.agent.application.project.tool.web.WebFetchResponse;
import io.haifa.agent.application.project.tool.web.WebProviderCapabilities;
import io.haifa.agent.application.project.tool.web.WebProviderDescriptor;
import io.haifa.agent.application.project.tool.web.WebProviderId;
import io.haifa.agent.application.project.tool.web.WebProviderInvocationContext;
import io.haifa.agent.credential.api.CredentialRequirement;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class AliyunFetchProvider implements WebFetchProvider {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://cloud-iqs.aliyuncs.com/readpage/basic");
    public static final CredentialRequirement CREDENTIAL =
            WebHttpSupport.credential("web-fetch-aliyun", "Aliyun IQS web fetch", "web.fetch");
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final WebProviderDescriptor descriptor;

    public AliyunFetchProvider() {
        this(defaultClient(), new ObjectMapper(), DEFAULT_ENDPOINT, Duration.ofSeconds(30), 4 * 1024 * 1024);
    }

    public AliyunFetchProvider(
            HttpClient client, ObjectMapper mapper, URI endpoint, Duration timeout, int maxResponseBytes) {
        this(client, mapper, endpoint, timeout, maxResponseBytes, Clock.systemUTC());
    }

    public AliyunFetchProvider(
            HttpClient client, ObjectMapper mapper, URI endpoint, Duration timeout, int maxResponseBytes, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (maxResponseBytes < 1024) throw new IllegalArgumentException("maxResponseBytes must be at least 1024");
        this.maxResponseBytes = maxResponseBytes;
        this.descriptor = new WebProviderDescriptor(
                new WebProviderId("aliyun"),
                "Aliyun IQS Fetch",
                WebProviderCapabilities.fetchOnly(),
                "aliyun-iqs-read-page-basic",
                "1.0.0",
                endpoint,
                Set.of(endpoint.getHost().toLowerCase(java.util.Locale.ROOT)),
                Optional.of(CREDENTIAL),
                Map.of(
                        "maxAge", "0",
                        "maxResponseBytes", Integer.toString(maxResponseBytes),
                        "timeoutMillis", Long.toString(timeout.toMillis())));
    }

    @Override
    public WebProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WebFetchResponse fetch(WebFetchRequest request, WebProviderInvocationContext context) {
        return WebHttpSupport.withCredential(context, clock, key -> {
            JsonNode root = WebHttpSupport.postJson(
                    client,
                    mapper,
                    descriptor.endpoint(),
                    Map.of("url", request.url().toString(), "maxAge", 0),
                    Map.of("X-API-Key", key, "Authorization", "Bearer " + key),
                    timeout,
                    maxResponseBytes,
                    context,
                    clock);
            var providerError = WebHttpSupport.providerError(root);
            if (providerError != null) throw providerError;
            JsonNode data = root.path("data");
            if (!data.isObject()) {
                throw WebHttpSupport.failure(
                        WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                        WebDispatchState.ACKNOWLEDGED,
                        "Aliyun fetch response did not contain data");
            }
            SelectedContent selected = selectContent(data, request.preferredFormat());
            String complete = selected.content().trim();
            if (complete.isBlank()) {
                throw WebHttpSupport.failure(
                        WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                        WebDispatchState.ACKNOWLEDGED,
                        "Aliyun fetch response did not contain page content");
            }
            boolean truncated = complete.length() > request.maxCharacters();
            String content = truncated ? complete.substring(0, request.maxCharacters()) : complete;
            URI finalUrl = data.hasNonNull("url")
                    ? WebHttpSupport.absoluteUri(data.path("url").asText())
                    : request.url();
            String title = WebHttpSupport.bounded(data.path("title").asText(""), 1024);
            return new WebFetchResponse(
                    request.url(),
                    finalUrl,
                    title.isBlank() ? Optional.empty() : Optional.of(title),
                    content,
                    selected.format(),
                    selected.mediaType(),
                    Optional.of("UTF-8"),
                    WebHttpSupport.sha256(content),
                    truncated);
        });
    }

    private static SelectedContent selectContent(JsonNode data, WebContentFormat preferred) {
        String markdown = data.path("markdown").asText("");
        String text = data.path("text").asText("");
        String html = data.path("html").asText("");
        if (preferred == WebContentFormat.MARKDOWN && !markdown.isBlank()) {
            return new SelectedContent(markdown, WebContentFormat.MARKDOWN, "text/markdown");
        }
        if (preferred == WebContentFormat.TEXT && !text.isBlank()) {
            return new SelectedContent(text, WebContentFormat.TEXT, "text/plain");
        }
        if (!markdown.isBlank()) return new SelectedContent(markdown, WebContentFormat.MARKDOWN, "text/markdown");
        if (!text.isBlank()) return new SelectedContent(text, WebContentFormat.TEXT, "text/plain");
        return new SelectedContent(html, WebContentFormat.HTML, "text/html");
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private record SelectedContent(String content, WebContentFormat format, String mediaType) {}
}
