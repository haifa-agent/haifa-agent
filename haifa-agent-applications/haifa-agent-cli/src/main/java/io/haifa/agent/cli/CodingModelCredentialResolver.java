package io.haifa.agent.cli;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.CredentialResolver;
import io.haifa.agent.model.api.ResolvedCredential;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Resolves environment and personal Coding Auth credentials with per-process refresh single-flight. */
final class CodingModelCredentialResolver implements CredentialResolver {
    private static final String ENV_PREFIX = "env://";
    private static final String CODING_AUTH_PREFIX = "coding-auth://";

    private final Function<String, String> environment;
    private final CodingAuthFileStore store;
    private final Optional<CodexOAuthClientRegistration> registration;
    private final Optional<CodexTokenClient> tokenClient;
    private final Clock clock;
    private final Duration refreshSafetyWindow;
    private final Map<String, CompletableFuture<CodingAuthCredential>> refreshes = new ConcurrentHashMap<>();

    CodingModelCredentialResolver(
            Function<String, String> environment,
            CodingAuthFileStore store,
            Optional<CodexOAuthClientRegistration> registration,
            Optional<CodexTokenClient> tokenClient,
            Clock clock,
            Duration refreshSafetyWindow) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.registration = Objects.requireNonNull(registration, "registration must not be null");
        this.tokenClient = Objects.requireNonNull(tokenClient, "tokenClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.refreshSafetyWindow = Objects.requireNonNull(refreshSafetyWindow, "refreshSafetyWindow must not be null");
        if (refreshSafetyWindow.isNegative() || refreshSafetyWindow.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("refreshSafetyWindow is invalid");
        }
        if (registration.isPresent() != tokenClient.isPresent()) {
            throw new IllegalArgumentException("Codex registration and token client must be configured together");
        }
    }

    @Override
    public ResolvedCredential resolve(CredentialRef reference) {
        String value =
                Objects.requireNonNull(reference, "reference must not be null").value();
        if (value.startsWith(ENV_PREFIX)) return environment(value.substring(ENV_PREFIX.length()));
        if (!value.startsWith(CODING_AUTH_PREFIX)) {
            throw new IllegalArgumentException("unsupported Coding model credential reference");
        }
        String localReference = value.substring(CODING_AUTH_PREFIX.length());
        CodingAuthCredential credential = store.find(localReference)
                .orElseThrow(() -> new IllegalStateException("Coding model credential is unavailable"));
        if (credential.kind() == CodingAuthCredential.Kind.API_KEY) {
            return new ResolvedCredential(credential.apiKey());
        }
        if (credential.validBeyond(clock.instant().plus(refreshSafetyWindow))) {
            return new ResolvedCredential(credential.accessToken());
        }
        return new ResolvedCredential(refreshSingleFlight(credential).accessToken());
    }

    private ResolvedCredential environment(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("environment credential reference is invalid");
        }
        String value = environment.apply(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("environment credential is unavailable");
        return new ResolvedCredential(value.trim());
    }

    private CodingAuthCredential refreshSingleFlight(CodingAuthCredential observed) {
        CompletableFuture<CodingAuthCredential> created = new CompletableFuture<>();
        CompletableFuture<CodingAuthCredential> existing = refreshes.putIfAbsent(observed.reference(), created);
        if (existing != null) return await(existing);
        try {
            CodingAuthCredential refreshed = refresh(observed);
            created.complete(refreshed);
            return refreshed;
        } catch (RuntimeException exception) {
            created.completeExceptionally(exception);
            throw exception;
        } finally {
            refreshes.remove(observed.reference(), created);
        }
    }

    private CodingAuthCredential refresh(CodingAuthCredential observed) {
        CodexOAuthClientRegistration configured = registration.orElseThrow(
                () -> new IllegalStateException("Codex OAuth client registration is unavailable"));
        if (!configured.reference().equals(observed.clientRegistrationRef())) {
            throw new IllegalStateException("Codex credential requires reauthentication for this client registration");
        }
        CodingAuthCredential current = store.find(observed.reference())
                .orElseThrow(() -> new IllegalStateException("Coding model credential was removed during refresh"));
        if (current.validBeyond(clock.instant().plus(refreshSafetyWindow))) return current;
        if (!sameCredential(observed, current)) {
            throw new IllegalStateException("Coding model credential changed during refresh");
        }
        try {
            CodexTokenClient.TokenSet tokens = tokenClient.orElseThrow().refresh(current.refreshToken());
            if (!current.accountId().equals(tokens.accountId())) {
                throw new IllegalStateException("Codex account changed during token refresh");
            }
            CodingAuthCredential refreshed = CodingAuthCredential.oauth2(
                    current.reference(),
                    tokens.accessToken(),
                    tokens.refreshToken(),
                    tokens.expiresAtEpochMillis(),
                    tokens.accountId(),
                    current.clientRegistrationRef(),
                    tokens.issuedAtEpochMillis());
            store.save(refreshed);
            return refreshed;
        } catch (CodexTokenClient.CodexTokenException exception) {
            if (exception.retryable() && current.validBeyond(clock.instant())) return current;
            throw exception;
        }
    }

    private static CodingAuthCredential await(CompletableFuture<CodingAuthCredential> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw exception;
        }
    }

    private static boolean sameCredential(CodingAuthCredential first, CodingAuthCredential second) {
        return first.kind() == second.kind()
                && first.reference().equals(second.reference())
                && first.refreshToken().equals(second.refreshToken())
                && first.expiresAtEpochMillis() == second.expiresAtEpochMillis()
                && first.accountId().equals(second.accountId())
                && first.clientRegistrationRef().equals(second.clientRegistrationRef());
    }
}
