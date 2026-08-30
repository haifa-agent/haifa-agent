package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProgressView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.application.project.product.coding.client.CodingBrowserLoginView;
import io.haifa.agent.application.project.product.coding.client.CodingDeviceLoginView;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptSnapshot;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptState;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.LocalModelAuthenticationService;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityExternalLoginMethod;
import io.haifa.agent.auth.localmodel.codex.CodexExternalLoginMethod;
import io.haifa.agent.model.api.CredentialRef;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Highest-layer adapter from Coding Agent authentication use cases to shared local model auth. */
final class CliCodingAuthenticationClient implements CodingAuthenticationClient, AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final LocalModelAuthenticationService authentication;
    private final String selectedCredentialReference;
    private final String selectedProviderId;
    private final List<CredentialRef> availableCredentialReferences;
    private final boolean antigravityConnectionSupported;
    private final CodingAuthenticationMapper mapper;

    CliCodingAuthenticationClient(
            LocalModelAuthenticationService authentication,
            String selectedCredentialReference,
            String selectedProviderId) {
        this(
                authentication,
                selectedCredentialReference,
                selectedProviderId,
                List.of(selectedCredentialReference),
                false);
    }

    CliCodingAuthenticationClient(
            LocalModelAuthenticationService authentication,
            String selectedCredentialReference,
            String selectedProviderId,
            Collection<String> availableCredentialReferences) {
        this(authentication, selectedCredentialReference, selectedProviderId, availableCredentialReferences, false);
    }

    CliCodingAuthenticationClient(
            LocalModelAuthenticationService authentication,
            String selectedCredentialReference,
            String selectedProviderId,
            Collection<String> availableCredentialReferences,
            boolean antigravityConnectionSupported) {
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
        LinkedHashSet<CredentialRef> references = new LinkedHashSet<>();
        for (String reference : Objects.requireNonNull(
                availableCredentialReferences, "availableCredentialReferences must not be null")) {
            references.add(new CredentialRef(reference));
        }
        if (references.isEmpty()
                || references.stream().noneMatch(value -> value.value().equals(this.selectedCredentialReference))) {
            throw new IllegalArgumentException("availableCredentialReferences must contain the selected credential");
        }
        this.availableCredentialReferences = List.copyOf(references);
        this.antigravityConnectionSupported = antigravityConnectionSupported;
        this.mapper = new CodingAuthenticationMapper();
    }

    @Override
    public boolean connectionRequired() {
        return availableCredentialReferences.stream().allMatch(authentication::connectionRequired);
    }

    @Override
    public String apiKeyProviderId() {
        return selectedProviderId;
    }

    @Override
    public boolean apiKeyConnectionSupported() {
        return !selectedCredentialReference.startsWith("model-auth://openai-codex/")
                && !selectedCredentialReference.startsWith("model-auth://google-antigravity/");
    }

    @Override
    public boolean antigravityConnectionSupported() {
        return antigravityConnectionSupported;
    }

    @Override
    public List<CodingAuthenticationView> connections() {
        return authentication.connections().stream().map(mapper::view).toList();
    }

    @Override
    public CodingAuthenticationView loginCodexBrowser() {
        return loginCodexBrowser(instructions -> {});
    }

    @Override
    public CodingAuthenticationView loginCodexBrowser(Consumer<CodingBrowserLoginView> instructions) {
        return loginCodexBrowser(instructions, progress -> {});
    }

    @Override
    public CodingAuthenticationView loginCodexBrowser(
            Consumer<CodingBrowserLoginView> instructions, Consumer<CodingAuthenticationProgressView> progress) {
        ExternalLoginAttemptSnapshot started =
                authentication.startExternalLogin(CodexExternalLoginMethod.METHOD_ID, ExternalLoginMode.BROWSER);
        return await(
                started.attemptId(),
                Objects.requireNonNull(instructions, "instructions must not be null"),
                null,
                Objects.requireNonNull(progress, "progress must not be null"));
    }

    @Override
    public CodingAuthenticationView loginCodexDevice(Consumer<CodingDeviceLoginView> instructions) {
        return loginCodexDevice(instructions, progress -> {});
    }

    @Override
    public CodingAuthenticationView loginCodexDevice(
            Consumer<CodingDeviceLoginView> instructions, Consumer<CodingAuthenticationProgressView> progress) {
        ExternalLoginAttemptSnapshot started =
                authentication.startExternalLogin(CodexExternalLoginMethod.METHOD_ID, ExternalLoginMode.DEVICE_CODE);
        return await(
                started.attemptId(),
                null,
                Objects.requireNonNull(instructions, "instructions must not be null"),
                Objects.requireNonNull(progress, "progress must not be null"));
    }

    @Override
    public CodingAuthenticationView loginAntigravityBrowser(
            Consumer<CodingBrowserLoginView> instructions, Consumer<CodingAuthenticationProgressView> progress) {
        ExternalLoginAttemptSnapshot started =
                authentication.startExternalLogin(AntigravityExternalLoginMethod.METHOD_ID, ExternalLoginMode.BROWSER);
        return await(
                started.attemptId(),
                Objects.requireNonNull(instructions, "instructions must not be null"),
                null,
                Objects.requireNonNull(progress, "progress must not be null"));
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
            ExternalLoginAttemptId attemptId,
            Consumer<CodingBrowserLoginView> browserInstructions,
            Consumer<CodingDeviceLoginView> deviceInstructions,
            Consumer<CodingAuthenticationProgressView> progress) {
        AtomicBoolean instructionsSent = new AtomicBoolean();
        ExternalLoginAttemptState lastProgressState = null;
        while (true) {
            ExternalLoginAttemptSnapshot snapshot = authentication.attempt(attemptId);
            if (lastProgressState != snapshot.state()) {
                lastProgressState = snapshot.state();
                progressPhase(snapshot.state())
                        .ifPresent(phase -> progress.accept(new CodingAuthenticationProgressView(phase)));
            }
            if (browserInstructions != null
                    && snapshot.mode() == ExternalLoginMode.BROWSER
                    && instructionsSent.compareAndSet(false, true)) {
                var authorizationUri = authentication.takeBrowserAuthorizationUri(attemptId);
                if (authorizationUri.isPresent()) {
                    browserInstructions.accept(new CodingBrowserLoginView(
                            authorizationUri.orElseThrow(), snapshot.expiresAtEpochMillis()));
                } else {
                    instructionsSent.set(false);
                }
            }
            if (deviceInstructions != null
                    && snapshot.verificationUri().isPresent()
                    && snapshot.userCode().isPresent()
                    && instructionsSent.compareAndSet(false, true)) {
                deviceInstructions.accept(new CodingDeviceLoginView(
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

    static Optional<CodingAuthenticationProgressView.Phase> progressPhase(ExternalLoginAttemptState state) {
        return switch (state) {
            case CREATED, AUTHORIZING -> Optional.of(CodingAuthenticationProgressView.Phase.STARTING);
            case WAITING_USER -> Optional.of(CodingAuthenticationProgressView.Phase.WAITING_USER);
            case EXCHANGING -> Optional.of(CodingAuthenticationProgressView.Phase.EXCHANGING);
            case STORING -> Optional.of(CodingAuthenticationProgressView.Phase.STORING);
            case SUCCEEDED, FAILED, CANCELLED, EXPIRED -> Optional.empty();
        };
    }
}
