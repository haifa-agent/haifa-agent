package io.haifa.agent.auth.localmodel.opencode;

import io.haifa.agent.auth.localmodel.ExternalLoginMethod;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodDescriptor;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodId;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodUnavailableException;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.ExternalLoginOperation;
import io.haifa.agent.auth.localmodel.ExternalLoginOperationContext;
import io.haifa.agent.auth.localmodel.StoredExternalCredential;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * OpenCode Go subscription sign-in through the console device authorization grant.
 *
 * <p>This driver is a normal integration, not an unofficial local-compatibility peripheral: the grant is the
 * published RFC 8628 flow, the client identifier is a public string of the open source CLI, and the credential
 * only ever comes from an authorization the user approved. It therefore declares itself as supported by default
 * and exposes the authorization-code (device) mode only, so headless Linux hosts never need a browser.
 */
public final class OpenCodeGoExternalLoginMethod implements ExternalLoginMethod {
    public static final ExternalLoginMethodId METHOD_ID = new ExternalLoginMethodId("opencode-go");

    private static final Set<ExternalLoginMode> SUPPORTED_MODES = Set.of(ExternalLoginMode.DEVICE_CODE);

    private final OpenCodeGoTokenClient tokens;
    private final OpenCodeGoDeviceLoginOperation.Sleeper sleeper;
    private final ExternalLoginMethodDescriptor descriptor;

    public OpenCodeGoExternalLoginMethod(OpenCodeGoTokenClient tokens, OpenCodeGoDeviceLoginOperation.Sleeper sleeper) {
        this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
        this.descriptor = new ExternalLoginMethodDescriptor(
                METHOD_ID, "OpenCode Go sign-in", SUPPORTED_MODES, false, Optional.empty());
    }

    @Override
    public ExternalLoginMethodDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ExternalLoginOperation create(ExternalLoginMode mode, ExternalLoginOperationContext context) {
        return switch (Objects.requireNonNull(mode, "mode must not be null")) {
            case DEVICE_CODE -> new OpenCodeGoDeviceLoginOperation(tokens, context, sleeper);
            case BROWSER -> throw new ExternalLoginMethodUnavailableException("AUTH_LOGIN_MODE_UNAVAILABLE");
        };
    }

    /**
     * Refreshes a token pair. A different console account is treated as a re-authentication requirement, and an
     * advisory account lookup failure never discards a token pair the console just issued.
     */
    @Override
    public StoredExternalCredential refresh(StoredExternalCredential credential, Instant refreshBefore) {
        StoredExternalCredential current = Objects.requireNonNull(credential, "credential must not be null");
        Objects.requireNonNull(refreshBefore, "refreshBefore must not be null");
        if (!METHOD_ID.equals(current.methodId())
                || !OpenCodeGoEndpoints.CLIENT_REGISTRATION_REF.equals(current.clientRegistrationRef())) {
            throw new ExternalLoginMethodUnavailableException("AUTH_REAUTH_REQUIRED");
        }
        OpenCodeGoTokenClient.TokenSet refreshed;
        try {
            refreshed = tokens.refresh(current.refreshToken());
        } catch (OpenCodeGoTokenClient.OpenCodeGoTokenException exception) {
            throw new ExternalLoginMethodUnavailableException(
                    exception.retryable() ? "AUTH_LOGIN_SERVICE_UNAVAILABLE" : "AUTH_REAUTH_REQUIRED");
        }
        String accountId = current.accountId();
        try {
            String resolved = tokens.accountId(refreshed.accessToken());
            if (!accountId.equals(resolved)) {
                throw new ExternalLoginMethodUnavailableException("AUTH_REAUTH_REQUIRED");
            }
        } catch (OpenCodeGoTokenClient.OpenCodeGoTokenException ignored) {
            // Advisory check only: the refreshed token pair stays valid for the already bound account.
        }
        return new StoredExternalCredential(
                current.reference(),
                current.methodId(),
                current.clientRegistrationRef(),
                refreshed.accessToken(),
                refreshed.refreshToken(),
                refreshed.expiresAtEpochMillis(),
                refreshed.issuedAtEpochMillis(),
                accountId);
    }

    @Override
    public void revoke(StoredExternalCredential credential) {
        Objects.requireNonNull(credential, "credential must not be null");
        // The console contract exposes no documented token revocation endpoint.
        // Product logout deletes the local credential and never pretends remote revocation occurred.
    }
}
