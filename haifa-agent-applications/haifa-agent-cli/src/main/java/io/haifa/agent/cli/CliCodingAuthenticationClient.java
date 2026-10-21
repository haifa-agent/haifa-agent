package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationMethodView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProgressView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProviderView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.application.project.product.coding.client.CodingBrowserLoginView;
import io.haifa.agent.application.project.product.coding.client.CodingDeviceLoginView;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptSnapshot;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptState;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodDescriptor;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.LocalModelAuthenticationService;
import io.haifa.agent.auth.localmodel.LocalModelConnectionView;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityExternalLoginMethod;
import io.haifa.agent.auth.localmodel.codex.CodexExternalLoginMethod;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAuthenticationMethod;
import io.haifa.agent.model.core.ModelCatalogManifest;
import io.haifa.agent.model.core.ModelCatalogProvider;
import io.haifa.agent.model.core.PackagedModelCatalog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Highest-layer adapter from Coding Agent authentication use cases to shared local model auth. */
final class CliCodingAuthenticationClient implements CodingAuthenticationClient, AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final ModelCatalogManifest DEFAULT_CATALOG =
            PackagedModelCatalog.load(CliCodingAuthenticationClient.class.getClassLoader());

    private final LocalModelAuthenticationService authentication;
    private final String selectedCredentialReference;
    private final String selectedProviderId;
    private final List<CredentialRef> availableCredentialReferences;
    private final boolean antigravityConnectionSupported;
    private final ModelCatalogManifest catalog;
    private final CodingAuthenticationMapper mapper;
    private final AtomicReference<ExternalLoginAttemptId> activeLogin = new AtomicReference<>();

    CliCodingAuthenticationClient(
            LocalModelAuthenticationService authentication,
            String selectedCredentialReference,
            String selectedProviderId) {
        this(
                authentication,
                selectedCredentialReference,
                selectedProviderId,
                List.of(selectedCredentialReference),
                false,
                DEFAULT_CATALOG);
    }

    CliCodingAuthenticationClient(
            LocalModelAuthenticationService authentication,
            String selectedCredentialReference,
            String selectedProviderId,
            Collection<String> availableCredentialReferences) {
        this(
                authentication,
                selectedCredentialReference,
                selectedProviderId,
                availableCredentialReferences,
                false,
                DEFAULT_CATALOG);
    }

    CliCodingAuthenticationClient(
            LocalModelAuthenticationService authentication,
            String selectedCredentialReference,
            String selectedProviderId,
            Collection<String> availableCredentialReferences,
            boolean antigravityConnectionSupported) {
        this(
                authentication,
                selectedCredentialReference,
                selectedProviderId,
                availableCredentialReferences,
                antigravityConnectionSupported,
                DEFAULT_CATALOG);
    }

    CliCodingAuthenticationClient(
            LocalModelAuthenticationService authentication,
            String selectedCredentialReference,
            String selectedProviderId,
            Collection<String> availableCredentialReferences,
            boolean antigravityConnectionSupported,
            ModelCatalogManifest catalog) {
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
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
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
    public List<String> supportedApiKeyProviders() {
        List<String> catalogProviders = catalog.providers().stream()
                .filter(provider -> provider.authenticationMethods().contains(ModelAuthenticationMethod.API_KEY))
                .filter(provider -> hasWritableCredentialReference(provider.id().value()))
                .sorted(Comparator.comparingInt(ModelCatalogProvider::showOrder)
                        .thenComparing(provider -> provider.id().value()))
                .map(provider -> provider.id().value())
                .toList();
        LinkedHashSet<String> providers = new LinkedHashSet<>(catalogProviders);
        if (apiKeyConnectionSupported() && !providers.contains(selectedProviderId)) {
            providers.add(selectedProviderId);
        }
        return List.copyOf(providers);
    }

    @Override
    public boolean apiKeyConnectionSupported() {
        return hasWritableCredentialReference(selectedProviderId)
                && !selectedCredentialReference.startsWith("model-auth://openai-codex/")
                && !selectedCredentialReference.startsWith("model-auth://google-antigravity/");
    }

    private boolean hasWritableCredentialReference(String providerId) {
        String reference = "model-auth://" + providerId + "/default";
        return availableCredentialReferences.stream()
                .anyMatch(value -> value.value().equals(reference));
    }

    @Override
    public boolean codexConnectionSupported() {
        return true;
    }

    @Override
    public boolean antigravityConnectionSupported() {
        return antigravityConnectionSupported;
    }

    @Override
    public List<CodingAuthenticationProviderView> providers() {
        Map<String, LocalModelConnectionView> connections = authentication.connections().stream()
                .collect(Collectors.toMap(
                        LocalModelConnectionView::providerId, value -> value, (first, ignored) -> first));
        List<CodingAuthenticationProviderView> providers = new ArrayList<>();
        for (ModelCatalogProvider provider : catalog.providers()) {
            String providerId = provider.id().value();
            List<CodingAuthenticationMethodView> methods = methods(provider);
            if (methods.stream().noneMatch(CodingAuthenticationMethodView::available)) {
                continue;
            }
            LocalModelConnectionView connection = connections.get(providerId);
            boolean subscription = provider.authenticationMethods().contains(ModelAuthenticationMethod.EXTERNAL_LOGIN);
            providers.add(new CodingAuthenticationProviderView(
                    providerId,
                    provider.displayName(),
                    subscription
                            ? CodingAuthenticationProviderView.Category.SUBSCRIPTION
                            : CodingAuthenticationProviderView.Category.API_KEY,
                    connection != null,
                    "openai-codex".equals(providerId),
                    providerId.equals(selectedProviderId),
                    connection == null ? Optional.empty() : Optional.of(maskAccountLabel(connection.accountLabel())),
                    Optional.empty(),
                    methods,
                    Optional.empty()));
        }
        return List.copyOf(providers);
    }

    private List<CodingAuthenticationMethodView> methods(ModelCatalogProvider provider) {
        List<CodingAuthenticationMethodView> methods = new ArrayList<>();
        String providerId = provider.id().value();
        if (provider.authenticationMethods().contains(ModelAuthenticationMethod.API_KEY)) {
            boolean writable = hasWritableCredentialReference(providerId);
            methods.add(new CodingAuthenticationMethodView(
                    "api_key",
                    CodingAuthenticationMethodView.Type.API_KEY,
                    writable,
                    writable ? Optional.empty() : Optional.of("AUTH_API_KEY_UNAVAILABLE")));
        }
        if (provider.authenticationMethods().contains(ModelAuthenticationMethod.EXTERNAL_LOGIN)) {
            Optional<ExternalLoginMethodDescriptor> descriptor = authentication.externalLoginMethods().stream()
                    .filter(value -> value.methodId().value().equals(providerId))
                    .findFirst();
            if (descriptor.isEmpty()) {
                methods.add(new CodingAuthenticationMethodView(
                        "browser",
                        CodingAuthenticationMethodView.Type.OAUTH,
                        false,
                        Optional.of("AUTH_LOGIN_METHOD_UNAVAILABLE")));
            } else {
                if (descriptor.orElseThrow().supportedModes().contains(ExternalLoginMode.BROWSER)) {
                    methods.add(new CodingAuthenticationMethodView(
                            "browser", CodingAuthenticationMethodView.Type.OAUTH, true, Optional.empty()));
                }
                if (descriptor.orElseThrow().supportedModes().contains(ExternalLoginMode.DEVICE_CODE)) {
                    methods.add(new CodingAuthenticationMethodView(
                            "device", CodingAuthenticationMethodView.Type.OAUTH, true, Optional.empty()));
                }
            }
        }
        return List.copyOf(methods);
    }

    private static String maskAccountLabel(String value) {
        String label = value == null ? "" : value.strip();
        if (label.isEmpty()) {
            return "已连接";
        }
        int at = label.indexOf('@');
        if (at > 0) {
            return label.substring(0, 1) + "***" + label.substring(at);
        }
        if (label.length() <= 4) {
            return "***";
        }
        return label.substring(0, 1) + "***" + label.substring(label.length() - 2);
    }

    @Override
    public boolean cancelLogin() {
        ExternalLoginAttemptId attemptId = activeLogin.get();
        return attemptId != null && authentication.cancel(attemptId);
    }

    @Override
    public boolean openAuthorization(java.net.URI authorizationUri) {
        if (authorizationUri == null
                || !("https".equalsIgnoreCase(authorizationUri.getScheme())
                        || "http".equalsIgnoreCase(authorizationUri.getScheme()))) {
            return false;
        }
        if (!java.awt.Desktop.isDesktopSupported()
                || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            return false;
        }
        try {
            java.awt.Desktop.getDesktop().browse(authorizationUri);
            return true;
        } catch (java.io.IOException | SecurityException exception) {
            return false;
        }
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
    public CodingAuthenticationView saveApiKey(
            String providerId, char[] apiKey, java.util.Map<String, String> attributes) {
        return mapper.view(authentication.saveApiKey(providerId, apiKey, attributes));
    }

    @Override
    public Optional<java.util.Map<String, String>> providerAttributes(String providerId) {
        return authentication
                .findApiKeyCredential(providerId)
                .map(io.haifa.agent.auth.localmodel.StoredApiKeyCredential::attributes);
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
        activeLogin.set(attemptId);
        try {
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
        } finally {
            activeLogin.compareAndSet(attemptId, null);
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
