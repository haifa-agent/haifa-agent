package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.application.project.product.coding.client.CodingDeviceLoginView;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptSnapshot;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptState;
import io.haifa.agent.auth.localmodel.ExternalLoginCoordinator;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodId;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.LocalModelAuthReference;
import io.haifa.agent.auth.localmodel.LocalModelAuthStore;
import io.haifa.agent.auth.localmodel.LocalModelConnectionView;
import io.haifa.agent.auth.localmodel.StoredApiKeyCredential;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/** Highest-layer adapter from Coding Agent authentication use cases to shared local model auth. */
final class CliCodingAuthenticationClient implements CodingAuthenticationClient, AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final LocalModelAuthStore store;
    private final Optional<ExternalLoginCoordinator> coordinator;
    private final String selectedCredentialReference;
    private final String selectedProviderId;
    private final Function<String, String> environment;

    CliCodingAuthenticationClient(
            LocalModelAuthStore store,
            Optional<ExternalLoginCoordinator> coordinator,
            String selectedCredentialReference,
            String selectedProviderId,
            Function<String, String> environment) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
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
    }

    @Override
    public boolean connectionRequired() {
        if (selectedCredentialReference.startsWith("env://")) {
            String value = environment.apply(selectedCredentialReference.substring("env://".length()));
            return value == null || value.isBlank();
        }
        if (selectedCredentialReference.startsWith("model-auth://")) {
            return store.find(LocalModelAuthReference.parse(selectedCredentialReference))
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
        return !selectedCredentialReference.startsWith("model-auth://openai-codex/");
    }

    @Override
    public List<CodingAuthenticationView> connections() {
        return store.listSafe().stream()
                .map(CliCodingAuthenticationClient::view)
                .toList();
    }

    @Override
    public CodingAuthenticationView loginCodexBrowser() {
        ExternalLoginCoordinator available =
                coordinator.orElseThrow(() -> new IllegalStateException("AUTH_EXTERNAL_APPROVAL_REQUIRED"));
        ExternalLoginAttemptSnapshot started =
                available.start(ExternalLoginMethodId.OPENAI_CODEX, ExternalLoginMode.BROWSER);
        return await(available, started.attemptId(), null);
    }

    @Override
    public CodingAuthenticationView loginCodexDevice(Consumer<CodingDeviceLoginView> instructions) {
        ExternalLoginCoordinator available =
                coordinator.orElseThrow(() -> new IllegalStateException("AUTH_EXTERNAL_APPROVAL_REQUIRED"));
        ExternalLoginAttemptSnapshot started =
                available.start(ExternalLoginMethodId.OPENAI_CODEX, ExternalLoginMode.DEVICE_CODE);
        return await(
                available, started.attemptId(), Objects.requireNonNull(instructions, "instructions must not be null"));
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
            StoredApiKeyCredential credential = new StoredApiKeyCredential(
                    LocalModelAuthReference.parse("model-auth://" + provider + "/default"), new String(secret));
            store.save(credential);
            return view(credential.safeView(false));
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    @Override
    public boolean logout(String connectionId) {
        return store.delete(LocalModelAuthReference.parse(connectionId));
    }

    @Override
    public void close() {
        coordinator.ifPresent(ExternalLoginCoordinator::close);
    }

    private static CodingAuthenticationView await(
            ExternalLoginCoordinator coordinator,
            ExternalLoginAttemptId attemptId,
            Consumer<CodingDeviceLoginView> instructions) {
        AtomicBoolean instructionsSent = new AtomicBoolean();
        while (true) {
            ExternalLoginAttemptSnapshot snapshot = coordinator.find(attemptId);
            if (instructions != null
                    && snapshot.verificationUri().isPresent()
                    && snapshot.userCode().isPresent()
                    && instructionsSent.compareAndSet(false, true)) {
                instructions.accept(new CodingDeviceLoginView(
                        snapshot.verificationUri().orElseThrow(),
                        snapshot.userCode().orElseThrow(),
                        snapshot.expiresAtEpochMillis()));
            }
            if (snapshot.state() == ExternalLoginAttemptState.SUCCEEDED) {
                return view(coordinator
                        .completedConnection(attemptId)
                        .orElseThrow(() -> new IllegalStateException("AUTH_STORE_FAILED")));
            }
            if (snapshot.state() == ExternalLoginAttemptState.FAILED
                    || snapshot.state() == ExternalLoginAttemptState.CANCELLED
                    || snapshot.state() == ExternalLoginAttemptState.EXPIRED) {
                throw new IllegalStateException(snapshot.reasonCode().orElse("AUTH_LOGIN_FAILED"));
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                coordinator.cancel(attemptId);
                throw new IllegalStateException("AUTH_CANCELLED", exception);
            }
        }
    }

    private static CodingAuthenticationView view(LocalModelConnectionView connection) {
        return new CodingAuthenticationView(
                connection.connectionId().value(),
                connection.providerId(),
                connection.method() == LocalModelConnectionView.Method.API_KEY
                        ? CodingAuthenticationView.Method.API_KEY
                        : CodingAuthenticationView.Method.CHATGPT_SUBSCRIPTION,
                switch (connection.status()) {
                    case AUTHENTICATED -> CodingAuthenticationView.Status.AUTHENTICATED;
                    case REAUTH_REQUIRED -> CodingAuthenticationView.Status.REAUTH_REQUIRED;
                    case RATE_LIMITED -> CodingAuthenticationView.Status.RATE_LIMITED;
                },
                connection.accountLabel(),
                Optional.empty(),
                connection.expiresAtEpochMillis(),
                connection.unofficialLocalCompatibility());
    }
}
