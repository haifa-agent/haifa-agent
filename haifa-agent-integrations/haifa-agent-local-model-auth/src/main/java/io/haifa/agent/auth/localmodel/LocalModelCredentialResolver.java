package io.haifa.agent.auth.localmodel;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.CredentialResolver;
import io.haifa.agent.model.api.ResolvedCredential;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Resolves env:// and model-auth:// credentials with per-process refresh single-flight. */
public final class LocalModelCredentialResolver implements CredentialResolver {
    private static final String ENV_PREFIX = "env://";
    private static final String MODEL_AUTH_PREFIX = "model-auth://";

    private final Function<String, String> environment;
    private final LocalModelAuthStore store;
    private final ExternalLoginRegistry registry;
    private final Clock clock;
    private final Duration refreshSafetyWindow;
    private final Map<LocalModelAuthReference, CompletableFuture<StoredExternalCredential>> refreshes =
            new ConcurrentHashMap<>();

    public LocalModelCredentialResolver(
            Function<String, String> environment,
            LocalModelAuthStore store,
            ExternalLoginRegistry registry,
            Clock clock,
            Duration refreshSafetyWindow) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.refreshSafetyWindow = Objects.requireNonNull(refreshSafetyWindow, "refreshSafetyWindow must not be null");
        if (refreshSafetyWindow.isNegative() || refreshSafetyWindow.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("refreshSafetyWindow is invalid");
        }
    }

    @Override
    public ResolvedCredential resolve(CredentialRef reference) {
        String value =
                Objects.requireNonNull(reference, "reference must not be null").value();
        if (value.startsWith(ENV_PREFIX)) return resolveEnvironment(value.substring(ENV_PREFIX.length()));
        if (!value.startsWith(MODEL_AUTH_PREFIX)) {
            throw new IllegalArgumentException("AUTH_CREDENTIAL_SCHEME_UNSUPPORTED");
        }
        LocalModelAuthReference localReference = LocalModelAuthReference.parse(value);
        StoredModelCredential credential =
                store.find(localReference).orElseThrow(() -> new IllegalStateException("AUTH_CREDENTIAL_UNAVAILABLE"));
        if (credential instanceof StoredApiKeyCredential apiKey) return new ResolvedCredential(apiKey.apiKey());
        StoredExternalCredential external = (StoredExternalCredential) credential;
        Instant refreshBefore = clock.instant().plus(refreshSafetyWindow);
        if (external.validBeyond(refreshBefore)) {
            registry.prepareIfRegistered(external);
            return new ResolvedCredential(external.accessToken());
        }
        StoredExternalCredential refreshed = refreshSingleFlight(external, refreshBefore);
        registry.prepareIfRegistered(refreshed);
        return new ResolvedCredential(refreshed.accessToken());
    }

    private ResolvedCredential resolveEnvironment(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("environment credential reference is invalid");
        }
        String value = environment.apply(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("environment credential is unavailable");
        return new ResolvedCredential(value.trim());
    }

    private StoredExternalCredential refreshSingleFlight(StoredExternalCredential observed, Instant refreshBefore) {
        CompletableFuture<StoredExternalCredential> created = new CompletableFuture<>();
        CompletableFuture<StoredExternalCredential> existing = refreshes.putIfAbsent(observed.reference(), created);
        if (existing != null) return await(existing);
        try {
            StoredExternalCredential refreshed = refresh(observed, refreshBefore);
            created.complete(refreshed);
            return refreshed;
        } catch (RuntimeException exception) {
            created.completeExceptionally(exception);
            throw exception;
        } finally {
            refreshes.remove(observed.reference(), created);
        }
    }

    private StoredExternalCredential refresh(StoredExternalCredential observed, Instant refreshBefore) {
        StoredModelCredential latest = store.find(observed.reference())
                .orElseThrow(() -> new IllegalStateException("AUTH_CREDENTIAL_REMOVED_DURING_REFRESH"));
        if (!(latest instanceof StoredExternalCredential current)) {
            throw new IllegalStateException("AUTH_REAUTH_REQUIRED");
        }
        if (current.validBeyond(refreshBefore)) return current;
        if (!sameCredential(observed, current)) throw new IllegalStateException("AUTH_CREDENTIAL_CHANGED");
        ExternalLoginMethod method = registry.require(current.methodId());
        try {
            StoredExternalCredential refreshed = method.refresh(current, refreshBefore);
            if (!refreshed.reference().equals(current.reference())
                    || !refreshed.methodId().equals(current.methodId())
                    || !refreshed.clientRegistrationRef().equals(current.clientRegistrationRef())
                    || !refreshed.accountId().equals(current.accountId())) {
                throw new IllegalStateException("AUTH_REAUTH_REQUIRED");
            }
            store.save(refreshed);
            return refreshed;
        } catch (ExternalLoginMethodUnavailableException exception) {
            if ("AUTH_LOGIN_SERVICE_UNAVAILABLE".equals(exception.reasonCode())
                    && current.validBeyond(clock.instant())) {
                return current;
            }
            throw exception;
        }
    }

    private static StoredExternalCredential await(CompletableFuture<StoredExternalCredential> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw exception;
        }
    }

    private static boolean sameCredential(StoredExternalCredential first, StoredExternalCredential second) {
        return first.reference().equals(second.reference())
                && first.methodId().equals(second.methodId())
                && first.clientRegistrationRef().equals(second.clientRegistrationRef())
                && first.refreshToken().equals(second.refreshToken())
                && first.expiresAtEpochMillis() == second.expiresAtEpochMillis()
                && first.accountId().equals(second.accountId());
    }
}
