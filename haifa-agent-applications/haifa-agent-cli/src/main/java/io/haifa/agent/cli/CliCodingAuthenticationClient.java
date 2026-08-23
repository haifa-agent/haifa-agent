package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.application.project.product.coding.client.CodingDeviceLoginView;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptSnapshot;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptState;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodId;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.LocalModelAuthenticationService;
import io.haifa.agent.model.api.CredentialRef;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Highest-layer adapter from Coding Agent authentication use cases to shared local model auth. */
final class CliCodingAuthenticationClient implements CodingAuthenticationClient, AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final LocalModelAuthenticationService authentication;
    private final String selectedCredentialReference;
    private final String selectedProviderId;
    private final CodingAuthenticationMapper mapper;

    CliCodingAuthenticationClient(
            LocalModelAuthenticationService authentication,
            String selectedCredentialReference,
            String selectedProviderId) {
        this.authentication = Objects.requireNonNull(authentication, "authentication must not be null");
        this.selectedCredentialReference = Objects.requireNonNull(
                        selectedCredentialReference, "selectedCredentialReference must not be null")
                .trim();
        this.selectedProviderId = Objects.requireNonNull(selectedProviderId, "selectedProviderId must not be null")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (!this.selectedProviderId.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("selectedProviderId is invalid");
        }
        this.mapper = new CodingAuthenticationMapper();
    }

    @Override
    public boolean connectionRequired() {
        return authentication.connectionRequired(new CredentialRef(selectedCredentialReference));
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
        return authentication.connections().stream().map(mapper::view).toList();
    }

    @Override
    public CodingAuthenticationView loginCodexBrowser() {
        ExternalLoginAttemptSnapshot started =
                authentication.startExternalLogin(ExternalLoginMethodId.OPENAI_CODEX, ExternalLoginMode.BROWSER);
        return await(started.attemptId(), null);
    }

    @Override
    public CodingAuthenticationView loginCodexDevice(Consumer<CodingDeviceLoginView> instructions) {
        ExternalLoginAttemptSnapshot started =
                authentication.startExternalLogin(ExternalLoginMethodId.OPENAI_CODEX, ExternalLoginMode.DEVICE_CODE);
        return await(started.attemptId(), Objects.requireNonNull(instructions, "instructions must not be null"));
    }

    @Override
    public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
        return mapper.view(authentication.saveApiKey(providerId, apiKey));
    }

    @Override
    public boolean logout(String connectionId) {
        return authentication.logout(connectionId);
    }

    @Override
    public void close() {
        authentication.close();
    }

    private CodingAuthenticationView await(
            ExternalLoginAttemptId attemptId, Consumer<CodingDeviceLoginView> instructions) {
        AtomicBoolean instructionsSent = new AtomicBoolean();
        while (true) {
            ExternalLoginAttemptSnapshot snapshot = authentication.attempt(attemptId);
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
                return mapper.view(authentication
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
                authentication.cancel(attemptId);
                throw new IllegalStateException("AUTH_CANCELLED", exception);
            }
        }
    }
}
