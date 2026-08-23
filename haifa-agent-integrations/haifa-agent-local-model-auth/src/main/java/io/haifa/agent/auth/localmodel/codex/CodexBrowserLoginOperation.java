package io.haifa.agent.auth.localmodel.codex;

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

/** One local browser OAuth operation with PKCE, state, exact loopback callback, and scoped cleanup. */
public final class CodexBrowserLoginOperation implements ExternalLoginOperation {
    public static final LocalModelAuthReference CREDENTIAL_REFERENCE =
            LocalModelAuthReference.parse("model-auth://openai-codex/default");
    private static final int MAX_QUERY_LENGTH = 8 * 1024;
    private static final String SCOPE = "openid profile email offline_access";

    private final CodexOAuthClientRegistration registration;
    private final CodexTokenClient tokens;
    private final ExternalLoginOperationContext context;
    private final CodexPkce pkce;
    private final Duration timeout;
    private final long expiresAtEpochMillis;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<String> authorizationCode = new CompletableFuture<>();
    private volatile ExternalLoginAttemptSnapshot snapshot;
    private volatile HttpServer callbackServer;
    private volatile ExecutorService callbackExecutor;

    public CodexBrowserLoginOperation(
            CodexOAuthClientRegistration registration,
            CodexTokenClient tokens,
            ExternalLoginOperationContext context,
            CodexPkce pkce,
            Duration timeout) {
        this.registration = Objects.requireNonNull(registration, "registration must not be null");
        this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.pkce = Objects.requireNonNull(pkce, "pkce must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("Codex login timeout is invalid");
        }
        this.expiresAtEpochMillis = Math.addExact(context.clock().millis(), timeout.toMillis());
        this.snapshot = snapshot(ExternalLoginAttemptState.CREATED, Optional.empty());
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
            progress(ExternalLoginAttemptState.WAITING_USER, Optional.empty());
            if (!context.browserLauncher().open(authorizationUri(verifier, stateToken))) {
                throw new IllegalStateException("AUTH_BROWSER_OPEN_FAILED");
            }
            String code = authorizationCode.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            checkCancelled();
            progress(ExternalLoginAttemptState.EXCHANGING, Optional.empty());
            CodexTokenClient.TokenSet tokenSet = tokens.exchange(code, verifier, registration.redirectUri());
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

    private StoredExternalCredential credential(CodexTokenClient.TokenSet tokenSet) {
        return new StoredExternalCredential(
                CREDENTIAL_REFERENCE,
                ExternalLoginMethodId.OPENAI_CODEX,
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
                Thread thread = new Thread(runnable, "haifa-codex-oauth-callback");
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
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "Authentication callback method is not allowed.");
                return;
            }
            URI request = exchange.getRequestURI();
            String rawQuery = request.getRawQuery();
            if (!registration.redirectUri().getPath().equals(request.getPath())
                    || rawQuery == null
                    || rawQuery.length() > MAX_QUERY_LENGTH) {
                respond(exchange, 400, "Authentication callback is invalid.");
                return;
            }
            String host = exchange.getRequestHeaders().getFirst("Host");
            String expectedHost = registration.redirectUri().getHost() + ":"
                    + registration.redirectUri().getPort();
            if (host == null || !host.equalsIgnoreCase(expectedHost)) {
                respond(exchange, 400, "Authentication callback host is invalid.");
                return;
            }
            Map<String, String> query = query(rawQuery);
            if (!expectedState.equals(query.get("state"))) {
                respond(exchange, 400, "Authentication state did not match.");
                return;
            }
            String code = query.get("code");
            if (code == null || code.isBlank() || code.length() > 16 * 1024) {
                respond(exchange, 400, "Authentication code is missing.");
                return;
            }
            if (!authorizationCode.complete(code)) {
                respond(exchange, 409, "Authentication callback was already consumed.");
                return;
            }
            respond(exchange, 200, "Authentication completed. You can close this window.");
        } catch (RuntimeException exception) {
            respond(exchange, 400, "Authentication callback could not be processed.");
        }
    }

    private URI authorizationUri(String verifier, String stateToken) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", registration.clientId());
        query.put("redirect_uri", registration.redirectUri().toString());
        query.put("scope", SCOPE);
        query.put("code_challenge", CodexPkce.challenge(verifier));
        query.put("code_challenge_method", "S256");
        query.put("state", stateToken);
        query.put("id_token_add_organizations", "true");
        query.put("codex_cli_simplified_flow", "true");
        query.put("originator", registration.originator());
        String encoded = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        return URI.create(registration.authorizationEndpoint() + "?" + encoded);
    }

    private void progress(ExternalLoginAttemptState state, Optional<String> reason) {
        snapshot = new ExternalLoginAttemptSnapshot(
                context.attemptId(),
                ExternalLoginMethodId.OPENAI_CODEX,
                ExternalLoginMode.BROWSER,
                state,
                Optional.empty(),
                Optional.empty(),
                expiresAtEpochMillis,
                reason);
        context.progressSink().accept(snapshot);
    }

    private ExternalLoginAttemptSnapshot snapshot(ExternalLoginAttemptState state, Optional<String> reason) {
        return new ExternalLoginAttemptSnapshot(
                context.attemptId(),
                ExternalLoginMethodId.OPENAI_CODEX,
                ExternalLoginMode.BROWSER,
                state,
                Optional.empty(),
                Optional.empty(),
                expiresAtEpochMillis,
                reason);
    }

    private void checkCancelled() {
        if (cancelled.get()) throw new IllegalStateException("AUTH_CANCELLED");
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String name = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            if (values.putIfAbsent(name, value) != null)
                throw new IllegalArgumentException("duplicate callback parameter");
        }
        return values;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = ("<!doctype html><meta charset=\"utf-8\"><title>Haifa Agent</title><p>" + message + "</p>")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
