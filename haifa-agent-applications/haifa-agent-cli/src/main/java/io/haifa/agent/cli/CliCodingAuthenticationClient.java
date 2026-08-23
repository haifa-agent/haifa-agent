package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.application.project.product.coding.client.CodingDeviceLoginView;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/** Highest-layer local implementation of Coding Agent authentication use cases. */
final class CliCodingAuthenticationClient implements CodingAuthenticationClient {
    private final CodingAuthFileStore store;
    private final Optional<CodexOAuthClientRegistration> registration;
    private final Optional<CodexTokenClient> tokens;
    private final CodexLoginAttempt.BrowserLauncher browser;
    private final String selectedCredentialReference;
    private final String selectedProviderId;
    private final Function<String, String> environment;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private final AtomicReference<Object> activeLogin = new AtomicReference<>();

    CliCodingAuthenticationClient(
            CodingAuthFileStore store,
            Optional<CodexOAuthClientRegistration> registration,
            Optional<CodexTokenClient> tokens,
            CodexLoginAttempt.BrowserLauncher browser,
            String selectedCredentialReference,
            String selectedProviderId,
            Function<String, String> environment,
            HttpClient http,
            ObjectMapper json,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.registration = Objects.requireNonNull(registration, "registration must not be null");
        this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
        this.browser = Objects.requireNonNull(browser, "browser must not be null");
        this.selectedCredentialReference = Objects.requireNonNull(
                        selectedCredentialReference, "selectedCredentialReference must not be null")
                .trim();
        this.selectedProviderId = Objects.requireNonNull(selectedProviderId, "selectedProviderId must not be null")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (!this.selectedProviderId.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("selectedProviderId is invalid");
        }
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (registration.isPresent() != tokens.isPresent()) {
            throw new IllegalArgumentException("Codex registration and token client must be configured together");
        }
    }

    @Override
    public boolean connectionRequired() {
        if (selectedCredentialReference.startsWith("env://")) {
            String value = environment.apply(selectedCredentialReference.substring("env://".length()));
            return value == null || value.isBlank();
        }
        if (selectedCredentialReference.startsWith("coding-auth://")) {
            return store.find(selectedCredentialReference.substring("coding-auth://".length()))
                    .isEmpty();
        }
        return false;
    }

    @Override
    public String apiKeyProviderId() {
        return selectedProviderId;
    }

    @Override
    public boolean apiKeyConnectionSupported() {
        return !selectedCredentialReference.startsWith("coding-auth://openai-codex/");
    }

    @Override
    public List<CodingAuthenticationView> connections() {
        return store.list().stream().map(this::view).toList();
    }

    @Override
    public CodingAuthenticationView loginCodexBrowser() {
        CodexOAuthClientRegistration configured =
                registration.orElseThrow(() -> new IllegalStateException("AUTH_EXTERNAL_APPROVAL_REQUIRED"));
        CodexLoginAttempt attempt = new CodexLoginAttempt(
                configured, tokens.orElseThrow(), store, browser, new SecureRandom(), Duration.ofMinutes(5));
        if (!activeLogin.compareAndSet(null, attempt)) {
            throw new IllegalStateException("AUTH_LOGIN_IN_PROGRESS");
        }
        try {
            attempt.execute();
            return store.find(CodexLoginAttempt.CREDENTIAL_REFERENCE)
                    .map(this::view)
                    .orElseThrow(() -> new IllegalStateException("AUTH_STORE_FAILED"));
        } catch (CodexTokenClient.CodexTokenException exception) {
            throw new IllegalStateException(
                    exception.status() == 400 ? "AUTH_REAUTH_REQUIRED" : "AUTH_TOKEN_EXCHANGE_FAILED", exception);
        } finally {
            activeLogin.compareAndSet(attempt, null);
        }
    }

    @Override
    public CodingAuthenticationView loginCodexDevice(Consumer<CodingDeviceLoginView> instructions) {
        CodexOAuthClientRegistration configured =
                registration.orElseThrow(() -> new IllegalStateException("AUTH_EXTERNAL_APPROVAL_REQUIRED"));
        CodexDeviceLoginAttempt attempt = new CodexDeviceLoginAttempt(
                configured, tokens.orElseThrow(), store, http, json, clock, CodexDeviceLoginAttempt.Sleeper.system());
        if (!activeLogin.compareAndSet(null, attempt)) {
            throw new IllegalStateException("AUTH_LOGIN_IN_PROGRESS");
        }
        try {
            attempt.execute(instructions);
            return store.find(CodexLoginAttempt.CREDENTIAL_REFERENCE)
                    .map(this::view)
                    .orElseThrow(() -> new IllegalStateException("AUTH_STORE_FAILED"));
        } catch (CodexTokenClient.CodexTokenException exception) {
            throw new IllegalStateException(
                    exception.status() == 400 ? "AUTH_REAUTH_REQUIRED" : "AUTH_TOKEN_EXCHANGE_FAILED", exception);
        } finally {
            activeLogin.compareAndSet(attempt, null);
        }
    }

    @Override
    public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
        String provider = Objects.requireNonNull(providerId, "providerId must not be null")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (!provider.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("AUTH_PROVIDER_INVALID");
        }
        char[] secret = Objects.requireNonNull(apiKey, "apiKey must not be null");
        try {
            if (secret.length < 1 || secret.length > 64 * 1024) {
                throw new IllegalArgumentException("AUTH_SECRET_INVALID");
            }
            String value = new String(secret);
            CodingAuthCredential credential = CodingAuthCredential.apiKey(provider + "/default", value);
            store.save(credential);
            return view(credential);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    @Override
    public boolean logout(String connectionId) {
        String reference = Objects.requireNonNull(connectionId, "connectionId must not be null")
                .trim();
        Object attempt = activeLogin.get();
        if (attempt instanceof CodexLoginAttempt browserAttempt
                && CodexLoginAttempt.CREDENTIAL_REFERENCE.equals(reference)) browserAttempt.cancel();
        if (attempt instanceof CodexDeviceLoginAttempt deviceAttempt
                && CodexLoginAttempt.CREDENTIAL_REFERENCE.equals(reference)) deviceAttempt.cancel();
        return store.delete(reference);
    }

    private CodingAuthenticationView view(CodingAuthCredential credential) {
        if (credential.kind() == CodingAuthCredential.Kind.API_KEY) {
            return new CodingAuthenticationView(
                    credential.reference(),
                    provider(credential.reference()),
                    CodingAuthenticationView.Method.API_KEY,
                    CodingAuthenticationView.Status.AUTHENTICATED,
                    "Saved API key",
                    Optional.empty(),
                    OptionalLong.empty(),
                    false);
        }
        boolean unofficial = registration
                .filter(value -> value.reference().equals(credential.clientRegistrationRef()))
                .map(CodexOAuthClientRegistration::unofficialLocalCompatibility)
                .orElse(credential.clientRegistrationRef().contains("local-compat"));
        return new CodingAuthenticationView(
                credential.reference(),
                "openai-codex",
                CodingAuthenticationView.Method.CHATGPT_SUBSCRIPTION,
                CodingAuthenticationView.Status.AUTHENTICATED,
                "ChatGPT account " + safeAccountLabel(credential.accountId()),
                Optional.empty(),
                OptionalLong.empty(),
                unofficial);
    }

    private static String provider(String reference) {
        int separator = reference.indexOf('/');
        return separator < 0 ? reference : reference.substring(0, separator);
    }

    private static String safeAccountLabel(String accountId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(accountId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
