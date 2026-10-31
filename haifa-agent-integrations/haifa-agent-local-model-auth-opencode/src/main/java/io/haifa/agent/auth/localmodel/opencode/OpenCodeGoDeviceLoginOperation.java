package io.haifa.agent.auth.localmodel.opencode;

import io.haifa.agent.auth.localmodel.ExternalLoginAttemptSnapshot;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptState;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.ExternalLoginOperation;
import io.haifa.agent.auth.localmodel.ExternalLoginOperationContext;
import io.haifa.agent.auth.localmodel.LocalModelAuthReference;
import io.haifa.agent.auth.localmodel.StoredExternalCredential;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One bounded OpenCode Go device-code (authorization code) login operation.
 *
 * <p>The flow needs no browser, no loopback callback port, and no desktop session: the user opens the printed
 * verification page on any device and types the printed user code. The operation never writes the credential
 * store; the product coordinator owns storage.
 */
public final class OpenCodeGoDeviceLoginOperation implements ExternalLoginOperation {
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(15);
    private static final Duration MAX_INTERVAL = Duration.ofSeconds(60);

    /** Stable local credential slot for the single OpenCode Go subscription connection. */
    public static final LocalModelAuthReference CREDENTIAL_REFERENCE =
            LocalModelAuthReference.parse("model-auth://opencode-go/default");

    private final OpenCodeGoTokenClient tokens;
    private final ExternalLoginOperationContext context;
    private final Sleeper sleeper;
    private final long expiresAtEpochMillis;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ExternalLoginAttemptSnapshot snapshot;

    public OpenCodeGoDeviceLoginOperation(
            OpenCodeGoTokenClient tokens, ExternalLoginOperationContext context, Sleeper sleeper) {
        this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
        this.expiresAtEpochMillis = Math.addExact(context.clock().millis(), MAX_LIFETIME.toMillis());
        this.snapshot = snapshot(ExternalLoginAttemptState.CREATED, Optional.empty(), Optional.empty());
    }

    @Override
    public ExternalLoginAttemptSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public StoredExternalCredential execute() {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("AUTH_ATTEMPT_ALREADY_USED");
        requireOpen();
        try {
            return awaitAuthorization();
        } catch (OpenCodeGoTokenClient.OpenCodeGoTokenException exception) {
            // The coordinator classifies failures by reason code, so the driver-internal exception type stays internal.
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private StoredExternalCredential awaitAuthorization() {
        OpenCodeGoTokenClient.DeviceCode device = tokens.requestDeviceCode();
        progress(
                ExternalLoginAttemptState.WAITING_USER,
                Optional.of(device.verificationUri()),
                Optional.of(device.userCode()));
        Duration interval = device.interval();
        while (context.clock().millis() < expiresAtEpochMillis) {
            checkCancelled();
            OpenCodeGoTokenClient.PollOutcome outcome = tokens.poll(device.deviceCode());
            if (outcome.complete()) {
                progress(ExternalLoginAttemptState.EXCHANGING, Optional.empty(), Optional.empty());
                OpenCodeGoTokenClient.TokenSet tokenSet = outcome.tokens();
                String accountId = tokens.accountId(tokenSet.accessToken());
                return new StoredExternalCredential(
                        CREDENTIAL_REFERENCE,
                        OpenCodeGoExternalLoginMethod.METHOD_ID,
                        OpenCodeGoEndpoints.CLIENT_REGISTRATION_REF,
                        tokenSet.accessToken(),
                        tokenSet.refreshToken(),
                        tokenSet.expiresAtEpochMillis(),
                        tokenSet.issuedAtEpochMillis(),
                        accountId);
            }
            if (outcome.slowDown()) interval = clamp(interval.plusSeconds(5));
            sleeper.sleep(interval);
        }
        throw new IllegalStateException("AUTH_DEVICE_CODE_EXPIRED");
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public void close() {
        closed.compareAndSet(false, true);
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("AUTH_ATTEMPT_CLOSED");
    }

    private void checkCancelled() {
        if (cancelled.get()) throw new IllegalStateException("AUTH_CANCELLED");
    }

    private static Duration clamp(Duration interval) {
        return interval.compareTo(MAX_INTERVAL) > 0 ? MAX_INTERVAL : interval;
    }

    private void progress(
            ExternalLoginAttemptState state, Optional<java.net.URI> verificationUri, Optional<String> userCode) {
        snapshot = snapshot(state, verificationUri, userCode);
        context.progressSink().accept(snapshot);
    }

    private ExternalLoginAttemptSnapshot snapshot(
            ExternalLoginAttemptState state, Optional<java.net.URI> verificationUri, Optional<String> userCode) {
        return new ExternalLoginAttemptSnapshot(
                context.attemptId(),
                OpenCodeGoExternalLoginMethod.METHOD_ID,
                ExternalLoginMode.DEVICE_CODE,
                state,
                verificationUri,
                userCode,
                expiresAtEpochMillis,
                Optional.empty());
    }

    /** Bounded waiting primitive; tests replace it so no wall-clock sleep happens. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration);

        static Sleeper system() {
            return duration -> {
                try {
                    Thread.sleep(duration.toMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("AUTH_CANCELLED", exception);
                }
            };
        }
    }
}
