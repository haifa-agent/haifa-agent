package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.CredentialResolver;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ProviderHealth;
import io.haifa.agent.model.api.ProviderHealthProbe;
import io.haifa.agent.model.api.ProviderHealthStatus;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Bounded authenticated OpenAI-compatible {@code GET /models} health probe. */
public final class OpenAiCompatibleHealthProbe implements ProviderHealthProbe {
    private final ModelProviderDefinition provider;
    private final HttpClient http;
    private final CredentialResolver credentials;
    private final Duration timeout;
    private final Clock clock;
    private final boolean allowInsecureHttp;

    public OpenAiCompatibleHealthProbe(
            ModelProviderDefinition provider,
            HttpClient http,
            CredentialResolver credentials,
            Duration timeout,
            Clock clock) {
        this(provider, http, credentials, timeout, clock, false);
    }

    public OpenAiCompatibleHealthProbe(
            ModelProviderDefinition provider,
            HttpClient http,
            CredentialResolver credentials,
            Duration timeout,
            Clock clock,
            boolean allowInsecureHttp) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.allowInsecureHttp = allowInsecureHttp;
        if (!"https".equalsIgnoreCase(provider.endpoint().getScheme())
                && !(allowInsecureHttp
                        && "http".equalsIgnoreCase(provider.endpoint().getScheme()))) {
            throw new IllegalArgumentException("provider endpoint must use HTTPS");
        }
    }

    @Override
    public ProviderHealth check() {
        Instant observedAt = clock.instant();
        try {
            String secret = credentials.resolve(provider.credentialRef()).value();
            HttpRequest request = HttpRequest.newBuilder(modelsUri())
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + secret)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                body.readNBytes(1025);
            }
            ProviderHealthStatus status =
                    switch (response.statusCode()) {
                        case 200 -> ProviderHealthStatus.HEALTHY;
                        case 429 -> ProviderHealthStatus.RATE_LIMITED;
                        case 401, 403 -> ProviderHealthStatus.UNAVAILABLE;
                        default ->
                            response.statusCode() >= 500
                                    ? ProviderHealthStatus.DEGRADED
                                    : ProviderHealthStatus.UNAVAILABLE;
                    };
            return new ProviderHealth(
                    provider.id(), status, "provider probe returned HTTP " + response.statusCode(), observedAt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ProviderHealth(
                    provider.id(), ProviderHealthStatus.UNAVAILABLE, "provider probe cancelled", observedAt);
        } catch (IOException | RuntimeException exception) {
            return new ProviderHealth(
                    provider.id(), ProviderHealthStatus.UNAVAILABLE, "provider probe failed", observedAt);
        }
    }

    private URI modelsUri() {
        String base = provider.endpoint().toString();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return URI.create(base + "/models");
    }
}
