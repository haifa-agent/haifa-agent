package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.client.*;
import io.haifa.coding.host.internal.CliCodingAuthenticationClient;
import io.haifa.coding.host.internal.CliLocalModelAuth;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** Trusted-local credential management: no model, workspace, Runtime or SQLite assembly. */
final class CodingAuthCommand {
    private static final Set<String> SAFE_REASONS = Set.of(
            "AUTH_CANCELLED",
            "AUTH_SECRET_INVALID",
            "AUTH_PROVIDER_INVALID",
            "AUTH_LOGIN_FAILED",
            "AUTH_METHOD_UNAVAILABLE",
            "AUTH_LOGIN_METHOD_UNAVAILABLE",
            "AUTH_DEVICE_CODE_UNAVAILABLE",
            "AUTH_EXTERNAL_APPROVAL_REQUIRED",
            "AUTH_STORE_FAILED",
            "AUTH_CREDENTIAL_UNAVAILABLE",
            "AUTH_SECURE_INPUT_REQUIRED",
            "AUTH_STORE_PLATFORM_UNSUPPORTED",
            "OS_CREDENTIAL_STORE_UNAVAILABLE");
    private final Function<Arguments, CodingAuthenticationClient> factory;
    private final Supplier<char[]> secureInput;

    CodingAuthCommand() {
        this(CodingAuthCommand::open, () -> {
            var console = System.console();
            if (console == null) throw new IllegalStateException("AUTH_SECURE_INPUT_REQUIRED");
            return console.readPassword("API key (hidden; empty input cancels): ");
        });
    }

    CodingAuthCommand(Function<Arguments, CodingAuthenticationClient> factory, Supplier<char[]> secureInput) {
        this.factory = factory;
        this.secureInput = secureInput;
    }

    int run(String[] args, PrintStream output, PrintStream error) {
        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (IllegalArgumentException failure) {
            error.println("[AUTH_ARGUMENTS_INVALID] Use auth --help. Secrets are never accepted as arguments.");
            return 2;
        }
        if (arguments.help()) {
            output.println(usage());
            return 0;
        }
        CodingAuthenticationClient client = null;
        try {
            client = factory.apply(arguments);
            if (arguments.action().equals("status")) {
                output.println("Local model connections (current OS user):");
                var connections = client.connections().stream()
                        .filter(value -> arguments.provider().isEmpty()
                                || value.providerId()
                                        .equals(arguments.provider().orElseThrow()))
                        .toList();
                if (connections.isEmpty()) output.println("No saved model connections.");
                connections.forEach(value ->
                        output.println(value.connectionId() + " · " + value.providerId() + " · " + value.status()));
                return 0;
            }
            if (arguments.action().equals("logout")) {
                output.println(
                        client.logout(arguments.connection().orElseThrow())
                                ? "Local connection removed."
                                : "Local connection was not present.");
                return 0;
            }
            String provider = arguments.provider().orElseThrow();
            output.println("Local credential target: " + provider + "/default · current OS user");
            CodingAuthenticationView connection;
            if (arguments.method().equals("api-key")) {
                if (!client.apiKeyConnectionSupported()) throw new IllegalStateException("AUTH_METHOD_UNAVAILABLE");
                char[] secret = secureInput.get();
                if (secret == null || secret.length == 0) {
                    output.println("Authentication cancelled; existing credentials kept.");
                    return 130;
                }
                try {
                    connection = client.saveApiKey(
                            provider,
                            secret,
                            client.providerAttributes(provider).orElse(Map.of()));
                } finally {
                    Arrays.fill(secret, '\0');
                }
            } else {
                var progress = (java.util.function.Consumer<CodingAuthenticationProgressView>)
                        value -> output.println("Authentication: " + value.phase());
                var browser = (java.util.function.Consumer<CodingBrowserLoginView>) value ->
                        output.println("Open this one-time authorization URL locally: " + value.authorizationUri());
                var device = (java.util.function.Consumer<CodingDeviceLoginView>)
                        value -> output.println("Open " + value.verificationUri() + " · code " + value.userCode());
                connection = switch (arguments.method()) {
                    case "device" -> client.loginDevice(provider, device, progress);
                    case "browser" ->
                        switch (provider) {
                            case "openai-codex" -> client.loginCodexBrowser(browser, progress);
                            case "google-antigravity" -> client.loginAntigravityBrowser(browser, progress);
                            default -> throw new IllegalStateException("AUTH_METHOD_UNAVAILABLE");
                        };
                    default -> throw new IllegalStateException("AUTH_METHOD_UNAVAILABLE");
                };
            }
            output.println("Saved local connection: " + connection.connectionId() + " · " + connection.status());
            output.println("In the remote TUI, reopen /account to refresh Host status; retry deliberately.");
            return 0;
        } catch (RuntimeException failure) {
            String reason = failure.getMessage();
            error.println("[" + (reason != null && SAFE_REASONS.contains(reason) ? reason : "AUTH_LOCAL_COMMAND_FAILED")
                    + "] Local authentication did not complete.");
            return 1;
        } finally {
            if (client instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    /* Attempt cancellation is best effort; never print provider failures. */
                }
            }
        }
    }

    private static CodingAuthenticationClient open(Arguments args) {
        Function<String, String> environment =
                key -> key.equals("HAIFA_CODING_AUTH_STORE") && args.store().isPresent()
                        ? args.store().orElseThrow()
                        : System.getenv(key);
        if ("file".equals(environment.apply("HAIFA_CODING_AUTH_STORE"))
                && !"Linux".equalsIgnoreCase(System.getProperty("os.name", "")))
            throw new IllegalStateException("AUTH_STORE_PLATFORM_UNSUPPORTED");
        var auth = CliLocalModelAuth.open(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(),
                environment);
        String provider = args.provider().orElse("deepseek");
        try {
            return new CliCodingAuthenticationClient(
                    auth.service(),
                    "model-auth://" + provider + "/default",
                    provider,
                    List.of("model-auth://" + provider + "/default"),
                    auth.antigravityAvailable());
        } catch (RuntimeException failure) {
            auth.service().close();
            throw failure;
        }
    }

    static String usage() {
        return """
                Usage: haifa auth <status|connect|logout> [options]
                  status [--provider ID] [--store windows|file]
                  connect --provider ID [--method api-key|browser|device] [--store windows|file]
                  logout --connection ID [--store windows|file]
                Run on the Host machine as the same OS user. This command never opens Runtime/SQLite.
                API keys are read only from the local console with echo disabled, never arguments or RPC.
                The file store requires an explicit opt-in on headless Linux; Windows uses Credential Manager.
                Browser/device modes reuse the product's registered login methods and their availability.
                haifa-coding supports the same auth subcommand.
                """;
    }

    record Arguments(
            String action,
            Optional<String> provider,
            String method,
            Optional<String> store,
            Optional<String> connection,
            boolean help) {
        static Arguments parse(String[] args) {
            if (args.length == 0 || Arrays.asList(args).contains("--help"))
                return new Arguments("status", Optional.empty(), "api-key", Optional.empty(), Optional.empty(), true);
            String action = args[0];
            if (!Set.of("status", "connect", "logout").contains(action)) throw new IllegalArgumentException();
            Map<String, String> options = new HashMap<>();
            for (int i = 1; i < args.length; i += 2) {
                if (!Set.of("--provider", "--method", "--store", "--connection").contains(args[i])
                        || i + 1 >= args.length
                        || options.putIfAbsent(args[i], args[i + 1]) != null) throw new IllegalArgumentException();
            }
            String provider = options.get("--provider");
            if (provider != null && !provider.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException();
            String method = options.getOrDefault("--method", "api-key");
            if (!Set.of("api-key", "browser", "device").contains(method)) throw new IllegalArgumentException();
            String store = options.get("--store");
            if (store != null && !Set.of("file", "windows").contains(store)) throw new IllegalArgumentException();
            String connection = options.get("--connection");
            if (connection != null
                    && (connection.isBlank()
                            || connection.length() > 128
                            || connection.codePoints().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException();
            if (action.equals("connect") && (provider == null || connection != null)
                    || action.equals("logout") && (connection == null || provider != null)
                    || !action.equals("connect") && options.containsKey("--method")
                    || action.equals("status") && connection != null) throw new IllegalArgumentException();
            return new Arguments(
                    action,
                    Optional.ofNullable(provider),
                    method,
                    Optional.ofNullable(store),
                    Optional.ofNullable(connection),
                    false);
        }
    }
}
