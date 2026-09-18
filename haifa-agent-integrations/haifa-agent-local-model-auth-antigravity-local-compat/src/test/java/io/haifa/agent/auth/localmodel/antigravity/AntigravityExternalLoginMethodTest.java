package io.haifa.agent.auth.localmodel.antigravity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodUnavailableException;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.LocalModelAuthReference;
import io.haifa.agent.auth.localmodel.StoredExternalCredential;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AntigravityExternalLoginMethodTest {
    @Test
    void prepareRestoresEachCredentialOncePerTokenIssue() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cloudcode/v1internal:loadCodeAssist", exchange -> {
            calls.incrementAndGet();
            byte[] body =
                    "{\"cloudaicompanionProject\":{\"id\":\"restored-project\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AntigravityOAuthClientRegistration registration = new AntigravityOAuthClientRegistration(
                    "registration",
                    "test-client",
                    "test-secret",
                    URI.create(base + "/auth"),
                    URI.create(base + "/token"),
                    URI.create(base + "/userinfo"),
                    URI.create(base + "/cloudcode"),
                    URI.create(base + "/daily"),
                    URI.create("http://127.0.0.1:51121/oauth-callback"),
                    List.of("scope"),
                    "Antigravity",
                    true,
                    true,
                    false);
            AntigravityTokenClient tokens = new AntigravityTokenClient(
                    HttpClient.newHttpClient(),
                    new ObjectMapper(),
                    Clock.systemUTC(),
                    Duration.ofSeconds(5),
                    registration);
            AtomicReference<AntigravityProjectAndQuota> restored = new AtomicReference<>();
            AntigravityExternalLoginMethod method = new AntigravityExternalLoginMethod(
                    registration,
                    tokens,
                    HttpClient.newHttpClient(),
                    new ObjectMapper(),
                    SecureRandom::new,
                    Duration.ofMinutes(5),
                    restored::set);
            StoredExternalCredential credential = new StoredExternalCredential(
                    LocalModelAuthReference.parse("model-auth://google-antigravity/default"),
                    AntigravityExternalLoginMethod.METHOD_ID,
                    "registration",
                    "persisted-access",
                    "persisted-refresh",
                    10_000,
                    1_000,
                    "user@example.com");

            method.prepare(credential);
            method.prepare(credential);

            StoredExternalCredential secondCredential = new StoredExternalCredential(
                    LocalModelAuthReference.parse("model-auth://google-antigravity/secondary"),
                    AntigravityExternalLoginMethod.METHOD_ID,
                    "registration",
                    "persisted-access-secondary",
                    "persisted-refresh-secondary",
                    10_000,
                    1_000,
                    "secondary@example.com");
            method.prepare(secondCredential);

            assertThat(restored.get().projectId()).isEqualTo("restored-project");
            assertThat(calls).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void descriptorIsSafeAndRefreshRejectsAnotherRegistrationBeforeNetwork() {
        AntigravityOAuthClientRegistration registration = AntigravityLocalCompatibilityRegistrationFactory.create(
                        Map.of(
                                "HAIFA_ANTIGRAVITY_LOCAL_COMPAT_TEST",
                                "true",
                                "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_ID",
                                "test-client",
                                "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_SECRET",
                                "test-secret"))
                .orElseThrow();
        AntigravityTokenClient tokens = new AntigravityTokenClient(
                HttpClient.newHttpClient(), new ObjectMapper(), Clock.systemUTC(), Duration.ofSeconds(5), registration);
        AntigravityExternalLoginMethod method =
                new AntigravityExternalLoginMethod(registration, tokens, SecureRandom::new, Duration.ofMinutes(5));

        assertThat(method.descriptor().methodId()).isEqualTo(AntigravityExternalLoginMethod.METHOD_ID);
        assertThat(method.descriptor().supportedModes()).containsExactly(ExternalLoginMode.BROWSER);

        StoredExternalCredential foreign = new StoredExternalCredential(
                LocalModelAuthReference.parse("model-auth://google-antigravity/foreign"),
                AntigravityExternalLoginMethod.METHOD_ID,
                "other-registration",
                "access",
                "refresh",
                5_000,
                1_000,
                "user@example.com");

        assertThatThrownBy(() -> method.refresh(foreign, Instant.now()))
                .isInstanceOf(ExternalLoginMethodUnavailableException.class)
                .hasMessage("AUTH_REAUTH_REQUIRED");
    }

    @Test
    void refreshRejectsChangedAccountBeforePublishingProjectProjection() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/token",
                exchange -> respond(
                        exchange,
                        "{\"access_token\":\"refreshed-access\",\"refresh_token\":\"refreshed-refresh\",\"expires_in\":3600}"));
        server.createContext("/userinfo", exchange -> respond(exchange, "{\"email\":\"other@example.com\"}"));
        server.createContext(
                "/cloudcode/v1internal:loadCodeAssist",
                exchange -> respond(exchange, "{\"cloudaicompanionProject\":{\"id\":\"other-project\"}}"));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AntigravityOAuthClientRegistration registration = new AntigravityOAuthClientRegistration(
                    "registration",
                    "test-client",
                    "test-secret",
                    URI.create(base + "/auth"),
                    URI.create(base + "/token"),
                    URI.create(base + "/userinfo"),
                    URI.create(base + "/cloudcode"),
                    URI.create(base + "/daily"),
                    URI.create("http://127.0.0.1:51121/oauth-callback"),
                    List.of("scope"),
                    "Antigravity",
                    true,
                    true,
                    false);
            AntigravityTokenClient tokens = new AntigravityTokenClient(
                    HttpClient.newHttpClient(),
                    new ObjectMapper(),
                    Clock.fixed(Instant.ofEpochMilli(2_000), java.time.ZoneOffset.UTC),
                    Duration.ofSeconds(5),
                    registration);
            AtomicReference<AntigravityProjectAndQuota> published = new AtomicReference<>();
            AntigravityExternalLoginMethod method = new AntigravityExternalLoginMethod(
                    registration,
                    tokens,
                    HttpClient.newHttpClient(),
                    new ObjectMapper(),
                    SecureRandom::new,
                    Duration.ofMinutes(5),
                    published::set);
            StoredExternalCredential current = new StoredExternalCredential(
                    LocalModelAuthReference.parse("model-auth://google-antigravity/default"),
                    AntigravityExternalLoginMethod.METHOD_ID,
                    "registration",
                    "current-access",
                    "current-refresh",
                    1_500,
                    1_000,
                    "user@example.com");

            assertThatThrownBy(() -> method.refresh(current, Instant.ofEpochMilli(1_400)))
                    .isInstanceOf(ExternalLoginMethodUnavailableException.class)
                    .hasMessage("AUTH_REAUTH_REQUIRED");
            assertThat(published).hasNullValue();
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
