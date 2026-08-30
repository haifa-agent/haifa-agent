package io.haifa.agent.auth.localmodel.antigravity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.auth.localmodel.ExternalLoginMethod;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodDescriptor;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodId;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodUnavailableException;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.ExternalLoginOperation;
import io.haifa.agent.auth.localmodel.ExternalLoginOperationContext;
import io.haifa.agent.auth.localmodel.StoredExternalCredential;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** External-login driver for Google Antigravity OAuth and CloudCode PA API access. */
public final class AntigravityExternalLoginMethod implements ExternalLoginMethod {
    public static final ExternalLoginMethodId METHOD_ID = new ExternalLoginMethodId("google-antigravity");

    private final AntigravityOAuthClientRegistration registration;
    private final AntigravityTokenClient tokens;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Supplier<SecureRandom> randoms;
    private final Duration browserTimeout;
    private final ExternalLoginMethodDescriptor descriptor;
    private final Consumer<AntigravityProjectAndQuota> projectSink;
    private final Object preparationLock = new Object();
    private final ConcurrentHashMap<String, Long> preparedCredentialVersions = new ConcurrentHashMap<>();

    public AntigravityExternalLoginMethod(
            AntigravityOAuthClientRegistration registration,
            AntigravityTokenClient tokens,
            HttpClient http,
            ObjectMapper json,
            Supplier<SecureRandom> randoms,
            Duration browserTimeout,
            Consumer<AntigravityProjectAndQuota> projectSink) {
        this.registration = Objects.requireNonNull(registration, "registration must not be null");
        this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.randoms = Objects.requireNonNull(randoms, "randoms must not be null");
        this.browserTimeout = Objects.requireNonNull(browserTimeout, "browserTimeout must not be null");
        this.projectSink = Objects.requireNonNull(projectSink, "projectSink must not be null");
        this.descriptor = new ExternalLoginMethodDescriptor(
                METHOD_ID,
                "Google sign-in (Antigravity)",
                Set.of(ExternalLoginMode.BROWSER),
                registration.unofficialLocalCompatibility(),
                Optional.empty());
    }

    public AntigravityExternalLoginMethod(
            AntigravityOAuthClientRegistration registration,
            AntigravityTokenClient tokens,
            HttpClient http,
            ObjectMapper json,
            Supplier<SecureRandom> randoms,
            Duration browserTimeout) {
        this(registration, tokens, http, json, randoms, browserTimeout, ignored -> {});
    }

    public AntigravityExternalLoginMethod(
            AntigravityOAuthClientRegistration registration,
            AntigravityTokenClient tokens,
            Supplier<SecureRandom> randoms,
            Duration browserTimeout) {
        this(
                registration,
                tokens,
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                randoms,
                browserTimeout,
                ignored -> {});
    }

    @Override
    public ExternalLoginMethodDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ExternalLoginOperation create(ExternalLoginMode mode, ExternalLoginOperationContext context) {
        return switch (Objects.requireNonNull(mode, "mode must not be null")) {
            case BROWSER ->
                new AntigravityBrowserLoginOperation(
                        registration, tokens, context, new AntigravityPkce(randoms.get()), browserTimeout, projectSink);
            case DEVICE_CODE -> throw new ExternalLoginMethodUnavailableException("AUTH_LOGIN_MODE_UNAVAILABLE");
        };
    }

    @Override
    public void prepare(StoredExternalCredential credential) {
        StoredExternalCredential current = requireCompatible(credential);
        String reference = current.reference().value();
        if (preparedCredentialVersions.getOrDefault(reference, -1L) == current.issuedAtEpochMillis()) return;
        synchronized (preparationLock) {
            if (preparedCredentialVersions.getOrDefault(reference, -1L) == current.issuedAtEpochMillis()) return;
            try {
                projectSink.accept(tokens.fetchProjectAndQuota(current.accessToken()));
                preparedCredentialVersions.put(reference, current.issuedAtEpochMillis());
            } catch (AntigravityTokenClient.AntigravityTokenException exception) {
                throw new ExternalLoginMethodUnavailableException(
                        exception.retryable() ? "AUTH_LOGIN_SERVICE_UNAVAILABLE" : "AUTH_REAUTH_REQUIRED");
            }
        }
    }

    @Override
    public StoredExternalCredential refresh(StoredExternalCredential credential, Instant refreshBefore) {
        StoredExternalCredential current = requireCompatible(credential);
        Objects.requireNonNull(refreshBefore, "refreshBefore must not be null");
        try {
            AntigravityTokenClient.TokenSet refreshed = tokens.refresh(current.refreshToken());
            if (!current.accountId().equals(refreshed.accountId())) {
                throw new ExternalLoginMethodUnavailableException("AUTH_REAUTH_REQUIRED");
            }
            projectSink.accept(refreshed.projectAndQuota());
            StoredExternalCredential result = new StoredExternalCredential(
                    current.reference(),
                    current.methodId(),
                    current.clientRegistrationRef(),
                    refreshed.accessToken(),
                    refreshed.refreshToken(),
                    refreshed.expiresAtEpochMillis(),
                    refreshed.issuedAtEpochMillis(),
                    refreshed.accountId());
            preparedCredentialVersions.put(result.reference().value(), result.issuedAtEpochMillis());
            return result;
        } catch (AntigravityTokenClient.AntigravityTokenException exception) {
            throw new ExternalLoginMethodUnavailableException(
                    exception.retryable() ? "AUTH_LOGIN_SERVICE_UNAVAILABLE" : "AUTH_REAUTH_REQUIRED");
        }
    }

    @Override
    public void revoke(StoredExternalCredential credential) {
        Objects.requireNonNull(credential, "credential must not be null");
        // No-op for Google Antigravity OAuth tokens, local credential removal is handled by store
    }

    private StoredExternalCredential requireCompatible(StoredExternalCredential credential) {
        StoredExternalCredential current = Objects.requireNonNull(credential, "credential must not be null");
        if (!METHOD_ID.equals(current.methodId())
                || !registration.reference().equals(current.clientRegistrationRef())) {
            throw new ExternalLoginMethodUnavailableException("AUTH_REAUTH_REQUIRED");
        }
        return current;
    }
}
