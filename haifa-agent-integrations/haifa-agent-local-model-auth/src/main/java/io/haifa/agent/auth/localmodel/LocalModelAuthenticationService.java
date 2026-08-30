package io.haifa.agent.auth.localmodel;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.CredentialResolver;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Product-neutral application service and sole public write boundary for local model authentication. */
public final class LocalModelAuthenticationService implements AutoCloseable {
    private static final int MAX_SECRET_LENGTH = 64 * 1024;

    private final LocalModelAuthStore store;
    private final Optional<ExternalLoginCoordinator> coordinator;
    private final CredentialResolver credentialResolver;
    private final Function<String, String> environment;

    public LocalModelAuthenticationService(
            LocalModelAuthStore store,
            Optional<ExternalLoginCoordinator> coordinator,
            CredentialResolver credentialResolver,
            Function<String, String> environment) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.credentialResolver = Objects.requireNonNull(credentialResolver, "credentialResolver must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    public List<LocalModelConnectionView> connections() {
        return store.listSafe();
    }

    public List<ExternalLoginMethodDescriptor> externalLoginMethods() {
        return coordinator.map(ExternalLoginCoordinator::descriptors).orElseGet(List::of);
    }

    public boolean connectionRequired(CredentialRef reference) {
        String value =
                Objects.requireNonNull(reference, "reference must not be null").value();
        if (value.startsWith("env://")) {
            String name = value.substring("env://".length());
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("AUTH_ENVIRONMENT_REFERENCE_INVALID");
            }
            String secret = environment.apply(name);
            return secret == null || secret.isBlank();
        }
        if (value.startsWith("model-auth://")) {
            return store.find(LocalModelAuthReference.parse(value)).isEmpty();
        }
        throw new IllegalArgumentException("AUTH_CREDENTIAL_REFERENCE_UNSUPPORTED");
    }

    public LocalModelConnectionView saveApiKey(String providerId, char[] secret) {
        char[] callerSecret = Objects.requireNonNull(secret, "secret must not be null");
        try {
            String provider = normalizeProvider(providerId);
            if (callerSecret.length < 1 || callerSecret.length > MAX_SECRET_LENGTH) {
                throw new IllegalArgumentException("AUTH_SECRET_INVALID");
            }
            StoredApiKeyCredential credential = new StoredApiKeyCredential(
                    LocalModelAuthReference.parse("model-auth://" + provider + "/default"), new String(callerSecret));
            store.save(credential);
            return credential.safeView(false);
        } finally {
            Arrays.fill(callerSecret, '\0');
        }
    }

    public ExternalLoginAttemptSnapshot startExternalLogin(ExternalLoginMethodId methodId, ExternalLoginMode mode) {
        return requireCoordinator().start(methodId, mode);
    }

    public ExternalLoginAttemptSnapshot attempt(ExternalLoginAttemptId attemptId) {
        return requireCoordinator().find(attemptId);
    }

    public Optional<LocalModelConnectionView> completedConnection(ExternalLoginAttemptId attemptId) {
        return requireCoordinator().completedConnection(attemptId);
    }

    public Optional<URI> takeBrowserAuthorizationUri(ExternalLoginAttemptId attemptId) {
        return requireCoordinator().takeBrowserAuthorizationUri(attemptId);
    }

    public boolean cancel(ExternalLoginAttemptId attemptId) {
        return requireCoordinator().cancel(attemptId);
    }

    public boolean logout(String connectionId) {
        return store.delete(LocalModelAuthReference.parse(connectionId));
    }

    public Optional<StoredExternalCredential> findExternalCredential(CredentialRef reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        if (!reference.value().startsWith("model-auth://")) {
            return Optional.empty();
        }
        LocalModelAuthReference localRef;
        try {
            localRef = LocalModelAuthReference.parse(reference.value());
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        return store.find(localRef)
                .filter(StoredExternalCredential.class::isInstance)
                .map(StoredExternalCredential.class::cast);
    }

    public Optional<String> findExternalAccountId(CredentialRef reference, ExternalLoginMethodId methodId) {
        Objects.requireNonNull(methodId, "methodId must not be null");
        return findExternalCredential(reference)
                .filter(cred -> methodId.equals(cred.methodId()))
                .map(StoredExternalCredential::accountId);
    }

    public CredentialResolver credentialResolver() {
        return credentialResolver;
    }

    @Override
    public void close() {
        coordinator.ifPresent(ExternalLoginCoordinator::close);
    }

    private ExternalLoginCoordinator requireCoordinator() {
        return coordinator.orElseThrow(() -> new IllegalStateException("AUTH_EXTERNAL_APPROVAL_REQUIRED"));
    }

    private static String normalizeProvider(String providerId) {
        String provider = Objects.requireNonNull(providerId, "providerId must not be null")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!provider.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("AUTH_PROVIDER_INVALID");
        }
        return provider;
    }
}
