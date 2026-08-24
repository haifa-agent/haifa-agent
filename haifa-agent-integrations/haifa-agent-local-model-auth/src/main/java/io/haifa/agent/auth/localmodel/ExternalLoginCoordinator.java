package io.haifa.agent.auth.localmodel;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Sole owner of bounded external-login attempt lifecycle and completion. */
public final class ExternalLoginCoordinator implements AutoCloseable {
    private static final Set<ExternalLoginAttemptState> TERMINAL = Set.of(
            ExternalLoginAttemptState.SUCCEEDED,
            ExternalLoginAttemptState.FAILED,
            ExternalLoginAttemptState.CANCELLED,
            ExternalLoginAttemptState.EXPIRED);

    private final ExternalLoginRegistry registry;
    private final LocalModelAuthStore store;
    private final Supplier<ExternalLoginAttemptId> ids;
    private final Clock clock;
    private final ExecutorService executor;
    private final ExternalLoginOperationContext.BrowserLauncher browserLauncher;
    private final int maximumAttempts;
    private final Map<ExternalLoginAttemptId, Attempt> attempts = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ExternalLoginCoordinator(
            ExternalLoginRegistry registry,
            LocalModelAuthStore store,
            Supplier<ExternalLoginAttemptId> ids,
            Clock clock,
            ExecutorService executor,
            ExternalLoginOperationContext.BrowserLauncher browserLauncher,
            int maximumAttempts) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.browserLauncher = Objects.requireNonNull(browserLauncher, "browserLauncher must not be null");
        if (maximumAttempts < 1 || maximumAttempts > 128) {
            throw new IllegalArgumentException("maximumAttempts is invalid");
        }
        this.maximumAttempts = maximumAttempts;
    }

    public ExternalLoginAttemptSnapshot start(ExternalLoginMethodId methodId, ExternalLoginMode mode) {
        Objects.requireNonNull(methodId, "methodId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        synchronized (lifecycleLock) {
            requireOpen();
            cleanupTerminalAttempts();
            if (attempts.size() >= maximumAttempts) throw new IllegalStateException("AUTH_ATTEMPT_LIMIT_REACHED");
            boolean active = attempts.values().stream()
                    .anyMatch(attempt ->
                            attempt.methodId.equals(methodId) && !TERMINAL.contains(attempt.snapshot.state()));
            if (active) throw new IllegalStateException("AUTH_LOGIN_IN_PROGRESS");

            ExternalLoginMethod method = registry.require(methodId);
            if (!method.descriptor().supportedModes().contains(mode)) {
                throw new ExternalLoginMethodUnavailableException("AUTH_LOGIN_MODE_UNAVAILABLE");
            }
            ExternalLoginAttemptId attemptId = Objects.requireNonNull(ids.get(), "attempt id must not be null");
            if (attempts.containsKey(attemptId)) throw new IllegalStateException("AUTH_ATTEMPT_ID_DUPLICATE");
            Attempt attempt = new Attempt(attemptId, methodId, mode, clock.millis());
            ExternalLoginOperationContext context = new ExternalLoginOperationContext(
                    attemptId, clock, browserLauncher, attempt::acceptBrowserAuthorization, attempt::acceptProgress);
            ExternalLoginOperation operation =
                    Objects.requireNonNull(method.create(mode, context), "external login operation must not be null");
            try {
                attempt.attach(operation);
            } catch (RuntimeException exception) {
                try {
                    operation.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
            attempts.put(attemptId, attempt);
            attempt.transition(ExternalLoginAttemptState.AUTHORIZING, Optional.empty());
            try {
                attempt.future = executor.submit(() -> execute(attempt));
            } catch (RejectedExecutionException exception) {
                attempts.remove(attemptId, attempt);
                attempt.closeOperation();
                throw new IllegalStateException("AUTH_COORDINATOR_UNAVAILABLE", exception);
            }
            return attempt.snapshot;
        }
    }

    public ExternalLoginAttemptSnapshot find(ExternalLoginAttemptId attemptId) {
        Attempt attempt = requireAttempt(attemptId);
        expireIfNeeded(attempt);
        return attempt.snapshot;
    }

    public boolean cancel(ExternalLoginAttemptId attemptId) {
        Attempt attempt = attempts.get(Objects.requireNonNull(attemptId, "attemptId must not be null"));
        if (attempt == null) return false;
        synchronized (attempt) {
            if (TERMINAL.contains(attempt.snapshot.state())) return false;
            RuntimeException failure = stopOperation(attempt);
            attempt.transition(ExternalLoginAttemptState.CANCELLED, Optional.of("AUTH_CANCELLED"));
            if (failure != null) throw failure;
            return true;
        }
    }

    public Optional<LocalModelConnectionView> completedConnection(ExternalLoginAttemptId attemptId) {
        Attempt attempt = requireAttempt(attemptId);
        expireIfNeeded(attempt);
        return Optional.ofNullable(attempt.completedConnection);
    }

    public Optional<URI> takeBrowserAuthorizationUri(ExternalLoginAttemptId attemptId) {
        return requireAttempt(attemptId).takeBrowserAuthorizationUri();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = null;
        for (Attempt attempt : new ArrayList<>(attempts.values())) {
            try {
                cancel(attempt.attemptId);
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        for (Attempt attempt : attempts.values()) {
            try {
                attempt.closeOperation();
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        executor.shutdownNow();
        if (failure != null) throw failure;
    }

    private void execute(Attempt attempt) {
        try {
            StoredExternalCredential credential = attempt.operation.execute();
            synchronized (attempt) {
                if (attempt.snapshot.state() == ExternalLoginAttemptState.CANCELLED) return;
                if (!credential.methodId().equals(attempt.methodId)) {
                    throw new IllegalStateException("AUTH_LOGIN_METHOD_MISMATCH");
                }
                store.save(credential);
                boolean unofficial =
                        registry.require(attempt.methodId).descriptor().unofficial();
                attempt.completedConnection = credential.safeView(unofficial);
                attempt.transition(ExternalLoginAttemptState.SUCCEEDED, Optional.empty());
            }
        } catch (RuntimeException exception) {
            synchronized (attempt) {
                if (attempt.snapshot.state() != ExternalLoginAttemptState.CANCELLED
                        && attempt.snapshot.state() != ExternalLoginAttemptState.EXPIRED) {
                    attempt.transition(ExternalLoginAttemptState.FAILED, Optional.of(reasonCode(exception)));
                }
            }
        } finally {
            attempt.closeOperation();
        }
    }

    private void expireIfNeeded(Attempt attempt) {
        ExternalLoginAttemptSnapshot snapshot = attempt.snapshot;
        if (!TERMINAL.contains(snapshot.state())
                && snapshot.expiresAtEpochMillis() > 0
                && clock.millis() >= snapshot.expiresAtEpochMillis()) {
            synchronized (attempt) {
                if (!TERMINAL.contains(attempt.snapshot.state())) {
                    RuntimeException failure = stopOperation(attempt);
                    attempt.transition(ExternalLoginAttemptState.EXPIRED, Optional.of("AUTH_ATTEMPT_EXPIRED"));
                    if (failure != null) throw failure;
                }
            }
        }
    }

    private static RuntimeException stopOperation(Attempt attempt) {
        RuntimeException failure = null;
        try {
            attempt.operation.cancel();
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            Future<?> future = attempt.future;
            if (future != null) future.cancel(true);
            try {
                attempt.closeOperation();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        return failure;
    }

    private Attempt requireAttempt(ExternalLoginAttemptId attemptId) {
        Attempt attempt = attempts.get(Objects.requireNonNull(attemptId, "attemptId must not be null"));
        if (attempt == null) throw new IllegalArgumentException("AUTH_ATTEMPT_NOT_FOUND");
        return attempt;
    }

    private void cleanupTerminalAttempts() {
        if (attempts.size() < maximumAttempts) return;
        attempts.values().stream()
                .filter(attempt -> TERMINAL.contains(attempt.snapshot.state()))
                .sorted(Comparator.comparingLong(attempt -> attempt.createdAtEpochMillis))
                .limit(Math.max(1, attempts.size() - maximumAttempts + 1L))
                .map(attempt -> attempt.attemptId)
                .toList()
                .forEach(attempts::remove);
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("AUTH_COORDINATOR_CLOSED");
    }

    private static String reasonCode(RuntimeException exception) {
        if (exception instanceof ExternalLoginMethodUnavailableException unavailable) {
            return unavailable.reasonCode();
        }
        String message = exception.getMessage();
        return message != null && message.matches("AUTH_[A-Z0-9_]{1,80}") ? message : "AUTH_LOGIN_FAILED";
    }

    private final class Attempt {
        private final ExternalLoginAttemptId attemptId;
        private final ExternalLoginMethodId methodId;
        private final ExternalLoginMode mode;
        private final long createdAtEpochMillis;
        private final AtomicBoolean operationClosed = new AtomicBoolean();
        private volatile ExternalLoginAttemptSnapshot snapshot;
        private volatile ExternalLoginOperation operation;
        private volatile Future<?> future;
        private volatile LocalModelConnectionView completedConnection;
        private URI browserAuthorizationUri;

        private Attempt(
                ExternalLoginAttemptId attemptId,
                ExternalLoginMethodId methodId,
                ExternalLoginMode mode,
                long createdAtEpochMillis) {
            this.attemptId = attemptId;
            this.methodId = methodId;
            this.mode = mode;
            this.createdAtEpochMillis = createdAtEpochMillis;
            this.snapshot = new ExternalLoginAttemptSnapshot(
                    attemptId,
                    methodId,
                    mode,
                    ExternalLoginAttemptState.CREATED,
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    Optional.empty());
        }

        private void attach(ExternalLoginOperation operation) {
            ExternalLoginAttemptSnapshot initial =
                    Objects.requireNonNull(operation.snapshot(), "snapshot must not be null");
            requireIdentity(initial);
            if (initial.state() != ExternalLoginAttemptState.CREATED
                    || initial.expiresAtEpochMillis() <= createdAtEpochMillis) {
                throw new IllegalArgumentException("AUTH_ATTEMPT_INITIAL_STATE_INVALID");
            }
            this.operation = operation;
            this.snapshot = initial;
        }

        private synchronized void acceptProgress(ExternalLoginAttemptSnapshot progress) {
            Objects.requireNonNull(progress, "progress must not be null");
            requireIdentity(progress);
            if (TERMINAL.contains(snapshot.state())) return;
            if (!Set.of(
                            ExternalLoginAttemptState.AUTHORIZING,
                            ExternalLoginAttemptState.WAITING_USER,
                            ExternalLoginAttemptState.EXCHANGING)
                    .contains(progress.state())) {
                throw new IllegalArgumentException("AUTH_ATTEMPT_PROGRESS_INVALID");
            }
            if (!validProgressTransition(snapshot.state(), progress.state())) {
                throw new IllegalArgumentException("AUTH_ATTEMPT_TRANSITION_INVALID");
            }
            snapshot = progress;
        }

        private synchronized void transition(ExternalLoginAttemptState next, Optional<String> reason) {
            if (TERMINAL.contains(snapshot.state())) return;
            if (!validCoordinatorTransition(snapshot.state(), next)) {
                throw new IllegalStateException("AUTH_ATTEMPT_TRANSITION_INVALID");
            }
            snapshot = snapshot.withState(next, reason);
            if (TERMINAL.contains(next)) browserAuthorizationUri = null;
        }

        private synchronized void acceptBrowserAuthorization(URI authorizationUri) {
            if (TERMINAL.contains(snapshot.state())) return;
            if (snapshot.state() != ExternalLoginAttemptState.WAITING_USER) {
                throw new IllegalStateException("AUTH_BROWSER_AUTHORIZATION_STATE_INVALID");
            }
            URI value = Objects.requireNonNull(authorizationUri, "authorizationUri must not be null")
                    .normalize();
            String query = value.getRawQuery();
            if (!value.isAbsolute()
                    || value.getHost() == null
                    || value.getRawUserInfo() != null
                    || value.getRawFragment() != null
                    || !("https".equalsIgnoreCase(value.getScheme()) || "http".equalsIgnoreCase(value.getScheme()))
                    || query == null
                    || query.isBlank()
                    || query.length() > 8 * 1024
                    || query.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("AUTH_BROWSER_AUTHORIZATION_URI_INVALID");
            }
            browserAuthorizationUri = value;
        }

        private synchronized Optional<URI> takeBrowserAuthorizationUri() {
            URI value = browserAuthorizationUri;
            browserAuthorizationUri = null;
            return Optional.ofNullable(value);
        }

        private void requireIdentity(ExternalLoginAttemptSnapshot candidate) {
            if (!attemptId.equals(candidate.attemptId())
                    || !methodId.equals(candidate.methodId())
                    || mode != candidate.mode()) {
                throw new IllegalArgumentException("AUTH_ATTEMPT_IDENTITY_MISMATCH");
            }
        }

        private void closeOperation() {
            ExternalLoginOperation current = operation;
            if (current != null && operationClosed.compareAndSet(false, true)) current.close();
        }
    }

    private static boolean validProgressTransition(ExternalLoginAttemptState current, ExternalLoginAttemptState next) {
        return switch (current) {
            case AUTHORIZING ->
                next == ExternalLoginAttemptState.AUTHORIZING
                        || next == ExternalLoginAttemptState.WAITING_USER
                        || next == ExternalLoginAttemptState.EXCHANGING;
            case WAITING_USER ->
                next == ExternalLoginAttemptState.WAITING_USER || next == ExternalLoginAttemptState.EXCHANGING;
            case EXCHANGING -> next == ExternalLoginAttemptState.EXCHANGING;
            default -> false;
        };
    }

    private static boolean validCoordinatorTransition(
            ExternalLoginAttemptState current, ExternalLoginAttemptState next) {
        if (TERMINAL.contains(next)) return !TERMINAL.contains(current);
        return current == ExternalLoginAttemptState.CREATED && next == ExternalLoginAttemptState.AUTHORIZING;
    }
}
