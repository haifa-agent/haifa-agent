package io.haifa.coding.host.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
import io.haifa.agent.auth.localmodel.ExternalLoginCoordinator;
import io.haifa.agent.auth.localmodel.ExternalLoginMethod;
import io.haifa.agent.auth.localmodel.ExternalLoginRegistry;
import io.haifa.agent.auth.localmodel.FileLocalModelAuthStore;
import io.haifa.agent.auth.localmodel.LocalModelAuthStore;
import io.haifa.agent.auth.localmodel.LocalModelAuthenticationService;
import io.haifa.agent.auth.localmodel.LocalModelCredentialResolver;
import io.haifa.agent.auth.localmodel.WindowsLocalModelAuthStore;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityExternalLoginMethod;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityLocalCompatibilityRegistrationFactory;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityProjectRegistry;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityTokenClient;
import io.haifa.agent.auth.localmodel.codex.CodexDeviceLoginOperation;
import io.haifa.agent.auth.localmodel.codex.CodexExternalLoginMethod;
import io.haifa.agent.auth.localmodel.codex.CodexLocalCompatibilityRegistrationFactory;
import io.haifa.agent.auth.localmodel.codex.CodexTokenClient;
import io.haifa.agent.auth.localmodel.opencode.OpenCodeGoDeviceLoginOperation;
import io.haifa.agent.auth.localmodel.opencode.OpenCodeGoEndpoints;
import io.haifa.agent.auth.localmodel.opencode.OpenCodeGoExternalLoginMethod;
import io.haifa.agent.auth.localmodel.opencode.OpenCodeGoTokenClient;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.model.api.CredentialRef;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** The same local authentication assembly for a running product and its separate trusted-local management CLI. */
public record CliLocalModelAuth(
        LocalModelAuthStore store,
        LocalModelAuthenticationService service,
        LocalModelCredentialResolver credentials,
        AntigravityProjectRegistry projects,
        boolean antigravityAvailable) {
    public static CliLocalModelAuth open(
            HttpClient http, ObjectMapper json, Function<String, String> environmentResolver) {
        Clock authClock = Clock.systemUTC();
        var authStore = store(json, System.getProperty("os.name", ""), environmentResolver);
        var codexRegistration = CodexLocalCompatibilityRegistrationFactory.create(environmentResolver);
        var antigravityRegistration = AntigravityLocalCompatibilityRegistrationFactory.create(environmentResolver);
        var antigravityProjects = new AntigravityProjectRegistry();
        List<ExternalLoginMethod> authMethods = new ArrayList<>();
        authMethods.add(new OpenCodeGoExternalLoginMethod(
                new OpenCodeGoTokenClient(http, json, authClock, Duration.ofSeconds(30), new OpenCodeGoEndpoints()),
                OpenCodeGoDeviceLoginOperation.Sleeper.system()));
        codexRegistration.ifPresent(registration -> authMethods.add(new CodexExternalLoginMethod(
                registration,
                new CodexTokenClient(http, json, authClock, Duration.ofSeconds(30), registration),
                http,
                json,
                SecureRandom::new,
                Duration.ofMinutes(5),
                CodexDeviceLoginOperation.Sleeper.system())));
        antigravityRegistration.ifPresent(registration -> authMethods.add(new AntigravityExternalLoginMethod(
                registration,
                new AntigravityTokenClient(http, json, authClock, Duration.ofSeconds(30), registration),
                http,
                json,
                SecureRandom::new,
                Duration.ofMinutes(5),
                projection -> antigravityProjects.record(
                        new CredentialRef("model-auth://google-antigravity/default"), projection))));
        var authRegistry = new ExternalLoginRegistry(authMethods);
        var credentials = new LocalModelCredentialResolver(
                environmentResolver, authStore, authRegistry, authClock, Duration.ofMinutes(5));
        var authIdentifiers = new UuidV7IdentifierGenerator();
        var authCoordinator = authMethods.isEmpty()
                ? Optional.<ExternalLoginCoordinator>empty()
                : Optional.of(new ExternalLoginCoordinator(
                        authRegistry,
                        authStore,
                        () -> new ExternalLoginAttemptId(authIdentifiers.nextValue()),
                        authClock,
                        Executors.newFixedThreadPool(2, runnable -> {
                            Thread thread = new Thread(runnable, "haifa-local-model-auth");
                            thread.setDaemon(true);
                            return thread;
                        }),
                        CliLocalModelAuth::openBrowser,
                        8));
        var authenticationService =
                new LocalModelAuthenticationService(authStore, authCoordinator, credentials, environmentResolver);
        return new CliLocalModelAuth(
                authStore,
                authenticationService,
                credentials,
                antigravityProjects,
                antigravityRegistration.isPresent());
    }

    public String storeMode() {
        return store instanceof FileLocalModelAuthStore ? "file" : "windows";
    }

    public static LocalModelAuthStore store(ObjectMapper json, String osName, Function<String, String> environment) {
        String configured = environment.apply("HAIFA_CODING_AUTH_STORE");
        if (configured == null || configured.isBlank() || "windows".equalsIgnoreCase(configured.trim())) {
            return WindowsLocalModelAuthStore.defaultStore(json);
        }
        if ("file".equalsIgnoreCase(configured.trim()) && "Linux".equalsIgnoreCase(osName)) {
            return FileLocalModelAuthStore.defaultStore(json);
        }
        throw new IllegalArgumentException(
                "HAIFA_CODING_AUTH_STORE=file is supported only on explicitly configured headless Linux hosts");
    }

    private static boolean openBrowser(URI uri) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false;
        try {
            Desktop.getDesktop().browse(uri);
            return true;
        } catch (IOException | SecurityException exception) {
            return false;
        }
    }
}
