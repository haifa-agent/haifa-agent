package io.haifa.agent.web.provider;

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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Browserless Content API adapter for JavaScript-rendered HTML. */
public final class BrowserlessFetchProvider implements WebFetchProvider {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://production-sfo.browserless.io/content");
    public static final CredentialRequirement CREDENTIAL =
            WebHttpSupport.credential("web-fetch-browserless", "Browserless web fetch", "web.fetch");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>([^<]{1,2048})</title>");

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final WebProviderDescriptor descriptor;

    public BrowserlessFetchProvider() {
        this(defaultClient(), new ObjectMapper(), DEFAULT_ENDPOINT, Duration.ofSeconds(30), 4 * 1024 * 1024);
    }

    public BrowserlessFetchProvider(
            HttpClient client, ObjectMapper mapper, URI endpoint, Duration timeout, int maxResponseBytes) {
        this(client, mapper, endpoint, timeout, maxResponseBytes, Clock.systemUTC());
    }

    public BrowserlessFetchProvider(
            HttpClient client, ObjectMapper mapper, URI endpoint, Duration timeout, int maxResponseBytes, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || !(endpoint.getScheme().equalsIgnoreCase("https")
                        || endpoint.getScheme().equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("Browserless endpoint must be an absolute HTTP(S) URI");
        }
        if (endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Browserless endpoint must not contain user info, query, or fragment");
        }
        if (maxResponseBytes < 1024) throw new IllegalArgumentException("maxResponseBytes must be at least 1024");
        this.maxResponseBytes = maxResponseBytes;
        this.descriptor = new WebProviderDescriptor(
                new WebProviderId("browserless"),
                "Browserless Content",
                WebProviderCapabilities.fetchOnly(),
                "browserless-content",
                "1.0.0",
                endpoint,
                Set.of(endpoint.getHost().toLowerCase(java.util.Locale.ROOT)),
                Optional.of(CREDENTIAL),
                Map.of(
                        "maxResponseBytes", Integer.toString(maxResponseBytes),
                        "responseFormat", "html",
                        "timeoutMillis", Long.toString(timeout.toMillis())));
    }

    @Override
    public WebProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WebFetchResponse fetch(WebFetchRequest request, WebProviderInvocationContext context) {
        return WebHttpSupport.withCredential(context, clock, token -> {
            WebHttpSupport.WebHttpResponse response = WebHttpSupport.postContent(
                    client,
                    mapper,
                    descriptor.endpoint(),
                    Map.of("url", request.url().toString()),
                    Map.of("Authorization", "Bearer " + token, "Cache-Control", "no-cache"),
                    "text/html",
                    timeout,
                    maxResponseBytes,
                    context,
                    clock);
            requireHtml(response);
            rejectFailedTarget(response);
            String complete = new String(response.body(), StandardCharsets.UTF_8).trim();
            if (complete.isBlank()) {
                throw WebHttpSupport.failure(
                        WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                        WebDispatchState.ACKNOWLEDGED,
                        "Browserless returned empty page content");
            }
            boolean truncated = complete.length() > request.maxCharacters();
            String content = truncated ? complete.substring(0, request.maxCharacters()) : complete;
            URI finalUrl = response.headers()
                    .firstValue("X-Response-URL")
                    .filter(value -> !value.isBlank())
                    .map(WebHttpSupport::absoluteUri)
                    .orElse(request.url());
            Optional<String> title = title(complete);
            return new WebFetchResponse(
                    request.url(),
                    finalUrl,
                    title,
                    content,
                    WebContentFormat.HTML,
                    "text/html",
                    Optional.of("UTF-8"),
                    WebHttpSupport.sha256(content),
                    truncated);
        });
    }

    private static void requireHtml(WebHttpSupport.WebHttpResponse response) {
        String mediaType = response.headers()
                .firstValue("Content-Type")
                .orElse("")
                .split(";", 2)[0]
                .trim();
        if (!mediaType.equalsIgnoreCase("text/html")) {
            throw WebHttpSupport.failure(
                    WebFailureCode.WEB_UNSUPPORTED_MEDIA_TYPE,
                    WebDispatchState.ACKNOWLEDGED,
                    "Browserless returned an unsupported media type");
        }
    }

    private static void rejectFailedTarget(WebHttpSupport.WebHttpResponse response) {
        response.headers().firstValue("X-Response-Code").ifPresent(value -> {
            try {
                if (Integer.parseInt(value) >= 400) {
                    throw WebHttpSupport.failure(
                            WebFailureCode.WEB_PROVIDER_FAILED,
                            WebDispatchState.ACKNOWLEDGED,
                            "Browserless target page returned an unsuccessful status");
                }
            } catch (NumberFormatException exception) {
                throw WebHttpSupport.failure(
                        WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                        WebDispatchState.ACKNOWLEDGED,
                        "Browserless returned an invalid target status");
            }
        });
    }

    private static Optional<String> title(String html) {
        var matcher = TITLE.matcher(html);
        if (!matcher.find()) return Optional.empty();
        String value = WebHttpSupport.bounded(matcher.group(1).replaceAll("\\s+", " "), 1024);
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
