package io.haifa.agent.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One local browser OAuth attempt with PKCE, state, exact loopback callback, and scoped cleanup. */
final class CodexLoginAttempt implements AutoCloseable {
    static final String CREDENTIAL_REFERENCE = "openai-codex/default";
    private static final int MAX_QUERY_LENGTH = 8 * 1024;
    private static final String SCOPE = "openid profile email offline_access";

    enum State {
        CREATED,
        WAITING_CALLBACK,
        EXCHANGING_TOKEN,
        SUCCEEDED,
        CANCELLED,
        FAILED
    }

    private final CodexOAuthClientRegistration registration;
    private final CodexTokenClient tokens;
    private final CodingAuthFileStore store;
    private final BrowserLauncher browser;
    private final SecureRandom random;
    private final Duration timeout;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CompletableFuture<String> authorizationCode = new CompletableFuture<>();
    private volatile State state = State.CREATED;
    private volatile HttpServer callbackServer;
    private volatile ExecutorService callbackExecutor;

    CodexLoginAttempt(
            CodexOAuthClientRegistration registration,
            CodexTokenClient tokens,
            CodingAuthFileStore store,
            BrowserLauncher browser,
            SecureRandom random,
            Duration timeout) {
        this.registration = Objects.requireNonNull(registration, "registration must not be null");
        this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.browser = Objects.requireNonNull(browser, "browser must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("Codex login timeout is invalid");
        }
    }

    Result execute() {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Codex login attempt is single use");
        String verifier = randomToken(64);
        String stateToken = randomToken(32);
        try {
            startCallbackServer(stateToken);
            state = State.WAITING_CALLBACK;
            URI authorizationUri = authorizationUri(verifier, stateToken);
            if (!browser.open(authorizationUri)) {
                throw new IllegalStateException("AUTH_BROWSER_OPEN_FAILED");
            }
            String code = authorizationCode.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (cancelled.get()) throw new IllegalStateException("AUTH_CANCELLED");
            state = State.EXCHANGING_TOKEN;
            CodexTokenClient.TokenSet tokenSet = tokens.exchange(code, verifier, registration.redirectUri());
            store.save(CodingAuthCredential.oauth2(
                    CREDENTIAL_REFERENCE,
                    tokenSet.accessToken(),
                    tokenSet.refreshToken(),
                    tokenSet.expiresAtEpochMillis(),
                    tokenSet.accountId(),
                    registration.reference(),
                    tokenSet.issuedAtEpochMillis()));
            state = State.SUCCEEDED;
            return new Result(
                    tokenSet.accountId(), registration.reference(), registration.unofficialLocalCompatibility());
        } catch (TimeoutException exception) {
            state = State.FAILED;
            throw new IllegalStateException("AUTH_CALLBACK_TIMEOUT", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            state = State.CANCELLED;
            throw new IllegalStateException("AUTH_CANCELLED", exception);
        } catch (ExecutionException exception) {
            state = cancelled.get() ? State.CANCELLED : State.FAILED;
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("AUTH_CALLBACK_FAILED", cause);
        } catch (RuntimeException exception) {
            state = cancelled.get() ? State.CANCELLED : State.FAILED;
            throw exception;
        } finally {
            close();
            verifier = null;
            stateToken = null;
        }
    }

    State state() {
        return state;
    }

    void cancel() {
        cancelled.set(true);
        state = State.CANCELLED;
        authorizationCode.completeExceptionally(new IllegalStateException("AUTH_CANCELLED"));
        close();
    }

    @Override
    public void close() {
        HttpServer server = callbackServer;
        callbackServer = null;
        if (server != null) server.stop(0);
        ExecutorService executor = callbackExecutor;
        callbackExecutor = null;
        if (executor != null) executor.shutdownNow();
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
        query.put("code_challenge", challenge(verifier));
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

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String name = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            if (values.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("duplicate callback parameter");
            }
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

    record Result(String accountId, String clientRegistrationRef, boolean unofficialLocalCompatibility) {}

    @FunctionalInterface
    interface BrowserLauncher {
        boolean open(URI uri);

        static BrowserLauncher system() {
            return uri -> {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    return false;
                }
                try {
                    Desktop.getDesktop().browse(uri);
                    return true;
                } catch (IOException | SecurityException exception) {
                    return false;
                }
            };
        }
    }
}
