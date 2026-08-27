package io.haifa.agent.auth.localmodel.antigravity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptSnapshot;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptState;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodId;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.ExternalLoginOperation;
import io.haifa.agent.auth.localmodel.ExternalLoginOperationContext;
import io.haifa.agent.auth.localmodel.LocalModelAuthReference;
import io.haifa.agent.auth.localmodel.StoredExternalCredential;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Local browser OAuth operation for Google Antigravity with PKCE, state check, and loopback callback on 51121. */
public final class AntigravityBrowserLoginOperation implements ExternalLoginOperation {
    private static final System.Logger LOGGER = System.getLogger(AntigravityBrowserLoginOperation.class.getName());
    public static final LocalModelAuthReference CREDENTIAL_REFERENCE =
            LocalModelAuthReference.parse("model-auth://google-antigravity/default");
    private static final int MAX_QUERY_LENGTH = 8 * 1024;

    private final AntigravityOAuthClientRegistration registration;
    private final AntigravityTokenClient tokens;
    private final ExternalLoginOperationContext context;
    private final AntigravityPkce pkce;
    private final Duration timeout;
    private final long expiresAtEpochMillis;
    private final Consumer<AntigravityProjectAndQuota> projectSink;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<String> authorizationCode = new CompletableFuture<>();
    private volatile ExternalLoginAttemptSnapshot snapshot;
    private volatile HttpServer callbackServer;
    private volatile ExecutorService callbackExecutor;

    public AntigravityBrowserLoginOperation(
            AntigravityOAuthClientRegistration registration,
            AntigravityTokenClient tokens,
            ExternalLoginOperationContext context,
            AntigravityPkce pkce,
            Duration timeout,
            Consumer<AntigravityProjectAndQuota> projectSink) {
        this.registration = Objects.requireNonNull(registration, "registration must not be null");
        this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.pkce = Objects.requireNonNull(pkce, "pkce must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        this.projectSink = Objects.requireNonNull(projectSink, "projectSink must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("Antigravity login timeout is invalid");
        }
        this.expiresAtEpochMillis = Math.addExact(context.clock().millis(), timeout.toMillis());
        this.snapshot = snapshot(ExternalLoginAttemptState.CREATED, Optional.empty());
    }

    public AntigravityBrowserLoginOperation(
            AntigravityOAuthClientRegistration registration,
            AntigravityTokenClient tokens,
            ExternalLoginOperationContext context,
            AntigravityPkce pkce,
            Duration timeout) {
        this(registration, tokens, context, pkce, timeout, ignored -> {});
    }

    @Override
    public ExternalLoginAttemptSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public StoredExternalCredential execute() {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("AUTH_ATTEMPT_ALREADY_USED");
        String verifier = pkce.verifier();
        String stateToken = pkce.state();
        try {
            startCallbackServer(stateToken);
            URI authorizationUri = authorizationUri(verifier, stateToken);
            context.browserLauncher().open(authorizationUri);
            progress(ExternalLoginAttemptState.WAITING_USER, Optional.empty());
            context.browserAuthorizationSink().accept(authorizationUri);
            String code = authorizationCode.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            checkCancelled();
            progress(ExternalLoginAttemptState.EXCHANGING, Optional.empty());
            AntigravityTokenClient.TokenSet tokenSet = tokens.exchange(code, verifier, registration.redirectUri());
            projectSink.accept(tokenSet.projectAndQuota());
            return credential(tokenSet);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("AUTH_CALLBACK_TIMEOUT", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AUTH_CANCELLED", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("AUTH_CALLBACK_FAILED", cause);
        } finally {
            verifier = null;
            stateToken = null;
            close();
        }
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        authorizationCode.completeExceptionally(new IllegalStateException("AUTH_CANCELLED"));
        close();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        HttpServer server = callbackServer;
        callbackServer = null;
        if (server != null) server.stop(0);
        ExecutorService executor = callbackExecutor;
        callbackExecutor = null;
        if (executor != null) executor.shutdownNow();
    }

    private StoredExternalCredential credential(AntigravityTokenClient.TokenSet tokenSet) {
        return new StoredExternalCredential(
                CREDENTIAL_REFERENCE,
                ExternalLoginMethodId.GOOGLE_ANTIGRAVITY,
                registration.reference(),
                tokenSet.accessToken(),
                tokenSet.refreshToken(),
                tokenSet.expiresAtEpochMillis(),
                tokenSet.issuedAtEpochMillis(),
                tokenSet.accountId());
    }

    private void startCallbackServer(String expectedState) {
        URI redirect = registration.redirectUri();
        try {
            InetSocketAddress address = new InetSocketAddress(redirect.getHost(), redirect.getPort());
            HttpServer server = HttpServer.create(address, 0);
            ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "haifa-antigravity-oauth-callback");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext(redirect.getPath(), exchange -> handleCallback(exchange, expectedState));
            server.start();
            callbackExecutor = executor;
            callbackServer = server;
        } catch (IOException exception) {
            close();
            throw new IllegalStateException("AUTH_CALLBACK_UNAVAILABLE", exception);
        }
    }

    private void handleCallback(HttpExchange exchange, String expectedState) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            URI requestUri = exchange.getRequestURI();
            String rawQuery = requestUri.getRawQuery();
            if (rawQuery == null || rawQuery.length() > MAX_QUERY_LENGTH) {
                sendResponse(exchange, 400, "Invalid Request");
                return;
            }
            Map<String, String> query = parseQuery(rawQuery);
            String state = query.get("state");
            if (state == null || !state.equals(expectedState)) {
                sendResponse(exchange, 400, "Invalid State");
                return;
            }
            String error = query.get("error");
            if (error != null && !error.isBlank()) {
                authorizationCode.completeExceptionally(
                        new IllegalStateException("AUTH_OAUTH_ERROR: " + sanitizeError(error)));
                sendResponse(exchange, 200, "Authorization declined or failed. You may close this window.");
                return;
            }
            String code = query.get("code");
            if (code == null || code.isBlank()) {
                sendResponse(exchange, 400, "Invalid Code");
                return;
            }
            authorizationCode.complete(code.trim());
            sendResponse(
                    exchange,
                    200,
                    "Google Antigravity authorization received. Completing sign-in; you may close this window.");
        } catch (Exception exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Error handling OAuth callback", exception);
            sendResponse(exchange, 500, "Internal Server Error");
        }
    }

    private URI authorizationUri(String verifier, String stateToken) {
        String challenge = AntigravityPkce.challenge(verifier);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", registration.clientId());
        params.put("response_type", "code");
        params.put("redirect_uri", registration.redirectUri().toString());
        params.put("scope", String.join(" ", registration.scopes()));
        params.put("state", stateToken);
        params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256");
        params.put("access_type", "offline");
        params.put("prompt", "consent");

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!query.isEmpty()) query.append('&');
            query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return URI.create(registration.authorizationEndpoint().toString() + "?" + query);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String param : query.split("&")) {
            int index = param.indexOf('=');
            if (index > 0) {
                String key = java.net.URLDecoder.decode(param.substring(0, index), StandardCharsets.UTF_8);
                String value = java.net.URLDecoder.decode(param.substring(index + 1), StandardCharsets.UTF_8);
                result.put(key, value);
            }
        }
        return result;
    }

    private static String sanitizeError(String error) {
        return error.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String responseText) throws IOException {
        byte[] bytes = ("<!DOCTYPE html><html><body><h3>" + responseText + "</h3></body></html>")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void progress(ExternalLoginAttemptState state, Optional<String> reason) {
        snapshot = snapshot(state, reason);
        context.progressSink().accept(snapshot);
    }

    private void checkCancelled() {
        if (cancelled.get()) throw new IllegalStateException("AUTH_CANCELLED");
    }

    private ExternalLoginAttemptSnapshot snapshot(ExternalLoginAttemptState state, Optional<String> reason) {
        return new ExternalLoginAttemptSnapshot(
                context.attemptId(),
                ExternalLoginMethodId.GOOGLE_ANTIGRAVITY,
                ExternalLoginMode.BROWSER,
                state,
                Optional.empty(),
                Optional.empty(),
                expiresAtEpochMillis,
                reason);
    }
}
