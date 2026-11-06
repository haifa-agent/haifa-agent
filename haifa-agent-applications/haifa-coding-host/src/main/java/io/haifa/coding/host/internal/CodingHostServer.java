package io.haifa.coding.host.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.project.domain.ProjectId;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground loopback CA Host over one trusted Coding product assembly. */
public final class CodingHostServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 256 * 1024;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int COMMAND_PERMITS = 8;
    private static final int CONTROL_PERMITS = 4;

    private final String incarnation;
    private final String displayName;
    private final java.net.URI endpoint;
    private final CodingBrowserAccess browserAccess;
    private final java.util.concurrent.ScheduledExecutorService browserExpiry;
    private final java.util.concurrent.ConcurrentHashMap<String, io.haifa.coding.api.CodingClient> productClients =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> configurationSummary;
    private final StandaloneCodingAgent agent;
    private final CodingSessionClient client;
    private final ProjectId projectId;
    private final CodingHostDispatcher dispatcher;
    private final CodingHostSubscriptions subscriptions;
    private final CodingHostClients clients;
    private final CodingHostApprovals approvals;
    private final CodingHostMaintenance maintenance;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Semaphore commandPermits = new Semaphore(COMMAND_PERMITS);
    private final Semaphore controlPermits = new Semaphore(CONTROL_PERMITS);
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .addMixIn(io.haifa.coding.api.CodingProtocol.Entry.class, OptionalToolDetails.class);

    private interface OptionalToolDetails {
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        io.haifa.coding.api.CodingProtocol.ToolDetails toolDetails();
    }

    private final CountDownLatch terminated = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, Object> recoveryReport;

    private CodingHostServer(
            StandaloneCodingAgent agent,
            InetAddress bind,
            int port,
            List<String> configurationSummary,
            boolean presentationEnabled)
            throws IOException {
        this(agent, bind, port, configurationSummary, presentationEnabled, "Haifa Coding", java.util.Optional.empty());
    }

    private CodingHostServer(
            StandaloneCodingAgent agent,
            InetAddress bind,
            int port,
            List<String> configurationSummary,
            boolean presentationEnabled,
            String displayName,
            java.util.Optional<io.haifa.coding.host.BrowserOptions> browserOptions)
            throws IOException {
        this.displayName = displayName;
        this.configurationSummary = List.copyOf(configurationSummary);
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.client = agent.client();
        this.projectId = agent.projectId();
        LocalCodingAgent local = agent.localAgent();
        this.incarnation = "host-" + local.identifiers().nextValue();
        this.approvals = new CodingHostApprovals(local.runtime(), local.identifiers(), local.time());
        this.subscriptions = new CodingHostSubscriptions(
                client,
                CodingHostSubscriptions.DEFAULT_MAXIMUM_SUBSCRIPTIONS,
                CodingHostSubscriptions.DEFAULT_QUEUE_CAPACITY,
                local.time(),
                CodingHostSubscriptions.DEFAULT_ATTACH_TIMEOUT,
                local.identifiers(),
                agent.toolOutputPreviewPublisher());
        this.clients = new CodingHostClients(
                local.identifiers(),
                local.time(),
                Duration.ofMinutes(30),
                CodingHostClients.DEFAULT_MAXIMUM_CLIENTS,
                clientId -> {
                    closeProductClient(clientId);
                });
        this.maintenance = new CodingHostMaintenance(projectId, local.codingSessions(), client);
        this.dispatcher = new CodingHostDispatcher(
                projectId,
                client,
                local.codingSessions(),
                local.runtime(),
                local.outcomes(),
                agent.authentication(),
                subscriptions,
                () -> CodingHostWorkspace.summary(local.workspaceRoot()),
                approvals);
        if (presentationEnabled) {

            dispatcher.enablePresentation(
                    (clientId, sessionId) ->
                            new io.haifa.agent.application.project.product.coding.presentation
                                    .CodingPresentationObservation(
                                    local.codingSessions(),
                                    client,
                                    new io.haifa.coding.api.CodingProtocol.Scope(
                                            "view-" + local.identifiers().nextValue(),
                                            incarnation,
                                            clientId,
                                            sessionId.value(),
                                            1,
                                            clientSource(clientId)),
                                    local.credentialRedactor(),
                                    () -> approvals
                                            .presentationStatus(clientId, sessionId)
                                            .map(status -> new io.haifa.coding.api.CodingProtocol.Consent(
                                                    status.state().name(),
                                                    status.expiresAt()
                                                            .map(java.time.Instant::toEpochMilli)
                                                            .orElse(null),
                                                    true))
                                            .orElseGet(() ->
                                                    new io.haifa.coding.api.CodingProtocol.Consent("ASK", null, false)),
                                    String.join("; ", configurationSummary) + "; "
                                            + CodingHostWorkspace.summary(local.workspaceRoot()),
                                    local::presentationEventStart,
                                    "native".equals(clientSource(clientId))
                                            && clients.capabilities(clientId)
                                                    .contains(CodingHostDispatcher.NATIVE_TOOL_DETAILS)),
                    local.credentialRedactor());
        }
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        String host = server.getAddress().getAddress().getHostAddress();
        if (host.contains(":")) host = "[" + host + "]";
        this.endpoint =
                java.net.URI.create("http://" + host + ":" + server.getAddress().getPort());
        this.browserAccess = browserOptions
                .map(options -> new CodingBrowserAccess(
                        options,
                        incarnation,
                        endpoint.toString(),
                        local.identifiers()::nextValue,
                        this::closeProductClient))
                .orElse(null);
        this.browserExpiry = browserAccess == null
                ? null
                : Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                        .daemon()
                        .name("coding-browser-expiry")
                        .factory());
        try {
            server.setExecutor(executor);
            server.createContext("/v1/rpc", this::handleRpc);
            server.createContext("/v1/events", this::handleEvents);
            server.createContext("/health", this::handleHealth);
            if (browserAccess != null) {
                var assets = new CodingStaticAssets(browserOptions.orElseThrow());
                server.createContext("/browser/v1/info", this::handleBrowserInfo);
                server.createContext("/browser/v1/pair", this::handleBrowserPair);
                server.createContext("/browser/v1/rpc", this::handleBrowserRpc);
                server.createContext("/browser/v1/events", this::handleBrowserEvents);
                server.createContext("/browser/", exchange -> {
                    if (checkBrowserHost(exchange)) assets.serve(exchange);
                });
                browserExpiry.scheduleAtFixedRate(
                        () -> {
                            try {
                                browserAccess.expire();
                            } catch (RuntimeException ignored) {
                            }
                        },
                        250,
                        250,
                        TimeUnit.MILLISECONDS);
            }
            this.recoveryReport = recoverSessions(local.codingSessions(), local.runtime());
            server.start();
            maintenance.start();
        } catch (RuntimeException | Error | IOException exception) {
            server.stop(0);
            if (browserExpiry != null) browserExpiry.shutdownNow();
            if (browserAccess != null) browserAccess.close();
            maintenance.close();
            executor.shutdownNow();
            subscriptions.closeAll();
            clients.close();
            approvals.close();
            throw exception;
        }
    }

    public static CodingHostServer start(Path workspace, Optional<Path> config, String bind, int port) {
        return start(workspace, config, bind, port, false);
    }

    public static CodingHostServer start(
            Path workspace, Optional<Path> config, String bind, int port, boolean presentation) {
        ConfigurationInput cli = ConfigurationInput.of(config);
        CodingHostConfiguration resolved = new CliConfigurationLoader().loadHostConfiguration(cli, workspace);
        CliConfiguration configuration = resolved.configuration();
        if (configuration.persistence().mode()
                == io.haifa.agent.application.project.persistence.ProjectPersistenceMode.MEMORY) {
            throw new IllegalStateException("HOST_DURABLE_PERSISTENCE_REQUIRED: Host requires SQLITE persistence");
        }
        StandaloneCodingAgent agent;
        try {
            agent = StandaloneCodingAgents.open(
                    workspace, configuration, new PrintStream(OutputStream.nullOutputStream()), ignored -> {});
        } catch (io.haifa.agent.application.project.persistence.CodingDatabaseOwnershipException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
        return start(agent, bind, port, resolved.summary(), presentation);
    }

    public static CodingHostServer start(StandaloneCodingAgent agent, String bind, int port) {
        return start(
                agent,
                bind,
                port,
                List.of(
                        "Host config source: programmatic product assembly",
                        "Workspace config overlay: not loaded by the Host CLI"));
    }

    public List<String> configurationSummary() {
        return configurationSummary;
    }

    private Map<String, Object> configurationSummary(JsonNode params) {
        if (params != null && !params.isNull() && !params.isObject()) {
            throw CodingHostException.invalidParams("params must be an object");
        }
        return Map.of("summary", configurationSummary);
    }

    public static CodingHostServer start(
            StandaloneCodingAgent agent, String bind, int port, List<String> configurationSummary) {
        return start(agent, bind, port, configurationSummary, false);
    }

    public static CodingHostServer start(
            StandaloneCodingAgent agent,
            String bind,
            int port,
            List<String> configurationSummary,
            boolean presentationEnabled) {
        try {
            return new CodingHostServer(agent, loopback(bind), port, configurationSummary, presentationEnabled);
        } catch (IOException exception) {
            agent.close();
            throw new IllegalStateException("CA Host listener failed to start", exception);
        } catch (RuntimeException exception) {
            agent.close();
            throw exception;
        }
    }

    public static CodingHostServer start(
            StandaloneCodingAgent agent, io.haifa.coding.host.HostOptions options, List<String> summary) {
        try {
            return new CodingHostServer(
                    agent,
                    loopback(options.bind()),
                    options.port(),
                    summary,
                    true,
                    options.displayName(),
                    options.browser());
        } catch (IOException failure) {
            throw new IllegalStateException("HOST_START_FAILED");
        }
    }

    public java.net.URI endpoint() {
        return endpoint;
    }

    public java.util.Optional<java.net.URI> browserUri() {
        return browserAccess == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(endpoint.resolve("/browser/"));
    }

    public io.haifa.coding.host.PairingChallenge newPairingChallenge() {
        if (browserAccess == null) throw CodingHostException.unsupported("Browser mode is disabled");
        return browserAccess.challenge();
    }

    public void revokeBrowserClient(String clientId) {
        if (browserAccess != null) browserAccess.revoke(clientId);
    }

    private String clientSource(String clientId) {
        return browserAccess != null && browserAccess.ownsClient(clientId) ? "browser" : "native";
    }

    private void closeProductClient(String clientId) {
        var binding = productClients.remove(clientId);
        if (binding != null) binding.close();
        subscriptions.closeClient(clientId);
        approvals.closeClient(clientId);
    }

    public io.haifa.coding.api.CodingClient embeddedClient() {
        if (closed.get()) throw new io.haifa.coding.api.CodingClientException("HOST_CLOSED", false);
        var offered = new java.util.HashSet<>(java.util.Set.of(
                "coding-product-v1",
                "session-presentation-v1",
                "session-consent-v1",
                CodingHostDispatcher.NATIVE_TOOL_DETAILS));
        if (subscriptions.previewAvailable()) offered.add("preview-v1");
        String clientId = clients.initialize(offered);
        approvals.initialize(clientId);
        var binding = createProductClient(clientId, "embedded", offered);
        productClients.put(clientId, binding);
        return binding;
    }

    private io.haifa.agent.application.project.product.coding.presentation.CodingProductBinding createProductClient(
            String clientId, String source, java.util.Set<String> capabilities) {
        var local = agent.localAgent();
        return new io.haifa.agent.application.project.product.coding.presentation.CodingProductBinding(
                new io.haifa.coding.api.CodingProtocol.ConnectionInfo(
                        incarnation,
                        clientId,
                        projectId.value(),
                        "1",
                        displayName,
                        capabilities.stream().sorted().toList(),
                        source),
                client,
                local.codingSessions(),
                local.runtime(),
                agent.authentication(),
                local.credentialRedactor(),
                sessionId ->
                        new io.haifa.agent.application.project.product.coding.presentation
                                .CodingPresentationObservation(
                                local.codingSessions(),
                                client,
                                new io.haifa.coding.api.CodingProtocol.Scope(
                                        "view-" + local.identifiers().nextValue(),
                                        incarnation,
                                        clientId,
                                        sessionId.value(),
                                        1,
                                        source.equals("browser") ? "browser" : "native"),
                                local.credentialRedactor(),
                                () -> approvals
                                        .presentationStatus(clientId, sessionId)
                                        .map(status -> new io.haifa.coding.api.CodingProtocol.Consent(
                                                status.state().name(),
                                                status.expiresAt()
                                                        .map(java.time.Instant::toEpochMilli)
                                                        .orElse(null),
                                                true))
                                        .orElseGet(() ->
                                                new io.haifa.coding.api.CodingProtocol.Consent("ASK", null, false)),
                                String.join("; ", configurationSummary),
                                local::presentationEventStart,
                                capabilities.contains(CodingHostDispatcher.NATIVE_TOOL_DETAILS)),
                new io.haifa.agent.application.project.product.coding.presentation.CodingProductBinding.Approvals() {
                    public io.haifa.agent.runtime.api.InteractionResponseReceipt allow(
                            io.haifa.agent.runtime.api.InteractionView pending, String key) {
                        return approvals.grant(clientId, pending, key);
                    }

                    public java.util.Optional<
                                    io.haifa.agent.application.project.product.coding.client.CodingApprovalPrompt>
                            prepare(io.haifa.agent.runtime.api.InteractionView pending) {
                        return approvals.prepare(clientId, pending);
                    }

                    public void reset(io.haifa.agent.core.session.AgentSessionId sessionId) {
                        approvals.reset(sessionId);
                    }
                },
                () -> configurationSummary,
                () -> {
                    clients.require(clientId);
                    approvals.renew(clientId);
                },
                source.equals("embedded") ? () -> clients.remove(clientId) : () -> {},
                agent.toolOutputPreviewPublisher());
    }

    private io.haifa.agent.application.project.product.coding.presentation.CodingProductBinding productClient(
            String clientId) {
        return (io.haifa.agent.application.project.product.coding.presentation.CodingProductBinding)
                productClients.computeIfAbsent(
                        clientId, id -> createProductClient(id, "native", clients.capabilities(id)));
    }

    private final ObjectMapper productJson = new ObjectMapper()
            .enable(
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);

    private Object dispatchProduct(String clientId, String method, JsonNode params) {
        if (!method.startsWith("product/")) return dispatcher.dispatch(clientId, method, params);
        return dispatchProduct(productClient(clientId), method, params);
    }

    private Object dispatchProduct(
            io.haifa.agent.application.project.product.coding.presentation.CodingProductBinding binding,
            String method,
            JsonNode params) {
        try {
            return switch (method) {
                case "product/query" ->
                    binding.query(productJson.treeToValue(params, io.haifa.coding.api.CodingProtocol.Query.class));
                case "product/command" -> {
                    var result = binding.executeAdmitted(
                            productJson.treeToValue(params, io.haifa.coding.api.CodingProtocol.CommandRequest.class));
                    subscriptions.refreshPresentations();
                    yield result;
                }
                default -> throw CodingHostException.unknownMethod(method);
            };
        } catch (io.haifa.coding.api.CodingClientException failure) {
            throw CodingHostException.conflict(failure.code(), "Product request is unavailable");
        } catch (io.haifa.agent.application.project.product.ProjectProductException failure) {
            throw CodingHostException.conflict(failure.code(), "Product request is unavailable");
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) {
            throw CodingHostException.invalidParams("Invalid product request");
        }
    }

    private Semaphore permitsFor(String method, JsonNode params) {
        if (method.equals("product/command")) {
            String operation = params == null
                    ? ""
                    : params.path("command").path("operation").asText();
            return java.util.Set.of(
                                    "cancel",
                                    "respond",
                                    "allowForSession",
                                    "prepareApproval",
                                    "resetConsent",
                                    "shellCancel")
                            .contains(operation)
                    ? controlPermits
                    : commandPermits;
        }
        return method.equals("product/query") || controlMethod(method) ? controlPermits : commandPermits;
    }

    private boolean checkBrowserHost(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        if (browserAccess == null) {
            writeError(exchange, null, 404, -32601, "BROWSER_DISABLED", "Browser mode is disabled");
            return false;
        }
        if (!endpoint.getRawAuthority().equals(exchange.getRequestHeaders().getFirst("Host"))) {
            writeError(exchange, null, 403, -32000, "BROWSER_HOST_REJECTED", "Invalid browser Host");
            return false;
        }
        return true;
    }

    private CodingBrowserAccess.Context browserContext(HttpExchange exchange) {
        if (exchange.getRequestURI().getRawQuery() != null
                && exchange.getRequestURI().getRawQuery().matches("(?i).*(?:grant|token|challenge|authorization)=.*"))
            throw CodingHostException.invalidParams("Authorization must not be in URLs");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        return browserAccess.admit(
                token,
                exchange.getRequestHeaders().getFirst("Origin"),
                exchange.getRequestHeaders().getFirst("X-CA-Client-ID"));
    }

    private JsonNode browserBody(HttpExchange exchange) throws IOException {
        if (!isJsonContentType(exchange)) throw CodingHostException.invalidParams("application/json is required");
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) throw CodingHostException.invalidParams("Request exceeds budget");
        try {
            var value = json.readTree(bytes);
            if (value == null || !value.isObject()) throw CodingHostException.invalidParams("Object request required");
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw CodingHostException.invalidParams("Malformed JSON");
        }
    }

    private void browserFailure(HttpExchange exchange, JsonNode id, CodingHostException failure) throws IOException {
        writeError(exchange, id, 403, failure.jsonRpcCode(), failure.reasonCode(), "Browser request is unavailable");
    }

    private void handleBrowserInfo(HttpExchange exchange) throws IOException {
        if (!checkBrowserHost(exchange) || rejectPath(exchange, "/browser/v1/info") || rejectMethod(exchange, "GET"))
            return;
        try (exchange) {
            try {
                browserAccess.validateOrigin(exchange.getRequestHeaders().getFirst("Origin"), false);
                writeResult(
                        exchange,
                        null,
                        Map.of(
                                "hostIncarnation",
                                incarnation,
                                "protocolVersion",
                                "1",
                                "browserAuthorization",
                                "browser-grant-v1",
                                "displayName",
                                displayName));
            } catch (CodingHostException denied) {
                browserFailure(exchange, null, denied);
            }
        }
    }

    private void handleBrowserPair(HttpExchange exchange) throws IOException {
        if (!checkBrowserHost(exchange) || rejectPath(exchange, "/browser/v1/pair") || rejectMethod(exchange, "POST"))
            return;
        try (exchange) {
            try {
                if (exchange.getRequestURI().getRawQuery() != null)
                    throw CodingHostException.invalidParams("Pairing proof must be in the request body");
                var body = browserBody(exchange);
                io.haifa.coding.api.CodingProtocol.PairingRequest proof;
                try {
                    proof = productJson.treeToValue(body, io.haifa.coding.api.CodingProtocol.PairingRequest.class);
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                    throw CodingHostException.invalidParams("Invalid pairing request");
                }
                var grant = browserAccess.exchange(
                        proof.challenge(),
                        proof.hostIncarnation(),
                        exchange.getRequestHeaders().getFirst("Origin"));
                writeResult(exchange, null, grant);
            } catch (CodingHostException denied) {
                browserFailure(exchange, null, denied);
            }
        }
    }

    private void handleBrowserRpc(HttpExchange exchange) throws IOException {
        if (!checkBrowserHost(exchange) || rejectPath(exchange, "/browser/v1/rpc") || rejectMethod(exchange, "POST"))
            return;
        JsonNode id = null;
        Semaphore permits = null;
        try (exchange) {
            try {
                var context = browserContext(exchange);
                var request = browserBody(exchange);
                id = request.get("id");
                if (!request.path("jsonrpc").asText().equals("2.0")
                        || !request.path("method").isTextual()
                        || id == null
                        || !(id.isTextual() || id.isIntegralNumber()))
                    throw CodingHostException.invalidParams("Invalid RPC envelope");
                String method = request.path("method").textValue();
                JsonNode params = request.path("params");
                permits = permitsFor(method, params);
                if (!permits.tryAcquire()) {
                    permits = null;
                    throw CodingHostException.conflict("HOST_BUSY", "Host capacity exhausted");
                }
                // Capture the existing binding before the final admission point. Revocation can close observations,
                // but an already admitted command must still execute outside the authorization lock.
                var captured = (io.haifa.agent.application.project.product.coding.presentation.CodingProductBinding)
                        productClients.get(context.clientId());
                if (method.equals("initialize")) {
                    context.requireValid();
                    Object raw = dispatcher.dispatch(context.clientId(), method, params);
                    var initialized = new LinkedHashMap<String, Object>();
                    ((Map<?, ?>) raw).forEach((k, v) -> initialized.put(k.toString(), v));
                    @SuppressWarnings("unchecked")
                    var selected = (java.util.Set<String>) initialized.get("optionalCapabilities");
                    context.negotiate(selected, previous -> {
                        // Registry installation and revoke share the short admission lock.
                        // Reinitialize refreshes only negotiation; it never creates a second client.
                        if (selected.contains(CodingHostApprovals.CAPABILITY)
                                && !previous.contains(CodingHostApprovals.CAPABILITY))
                            approvals.initialize(context.clientId());
                        if (!selected.contains(CodingHostApprovals.CAPABILITY))
                            approvals.closeClient(context.clientId());
                        productClients.computeIfAbsent(
                                context.clientId(), key -> createProductClient(key, "browser", selected));
                    });
                    initialized.put("clientId", context.clientId());
                    initialized.put("hostIncarnation", incarnation);
                    initialized.put("displayName", displayName);
                    initialized.put("clientSource", "browser");
                    initialized.put("browserAuthorization", "browser-grant-v1");
                    writeResult(exchange, id, initialized);
                    return;
                }
                if (method.startsWith("product/")) {
                    if (captured == null)
                        throw CodingHostException.conflict("CLIENT_CONTEXT_REQUIRED", "initialize is required");
                    context.requireCapability("coding-product-v1");
                    writeResult(exchange, id, dispatchProduct(captured, method, params));
                    return;
                }
                if (method.startsWith("presentation/"))
                    context.requireCapability(CodingHostDispatcher.PRESENTATION_CAPABILITY);
                else if (method.startsWith("preview/")) context.requireCapability("preview-v1");
                else if (!java.util.Set.of("client/renew", "client/close").contains(method))
                    throw CodingHostException.unsupported("Browser method is unavailable");
                context.requireValid();
                if (method.equals("client/renew")) {
                    context.renew();
                    approvals.renew(context.clientId());
                    subscriptions.refreshPresentations();
                    writeResult(exchange, id, Map.of("renewed", true));
                    return;
                }
                if (method.equals("client/close")) {
                    context.close();
                    writeResult(exchange, id, Map.of("closed", true));
                    return;
                }
                Object result = dispatcher.dispatch(context.clientId(), method, params);
                if (method.equals("presentation/subscribe") || method.equals("preview/subscribe")) {
                    String subscriptionId =
                            json.valueToTree(result).path("subscriptionId").asText();
                    context.own(
                            "sub-" + subscriptionId,
                            () -> subscriptions.unsubscribe(context.clientId(), subscriptionId));
                } else if (method.equals("presentation/close") || method.equals("preview/unsubscribe")) {
                    context.release("sub-" + params.path("subscriptionId").asText());
                }
                writeResult(exchange, id, result);
            } catch (CodingHostException denied) {
                browserFailure(exchange, id, denied);
            } catch (RuntimeException failure) {
                writeError(exchange, id, 500, -32603, "INTERNAL_ERROR", "Browser request failed safely");
            } finally {
                if (permits != null) permits.release();
            }
        }
    }

    private void handleBrowserEvents(HttpExchange exchange) throws IOException {
        if (!checkBrowserHost(exchange) || rejectPath(exchange, "/browser/v1/events") || rejectMethod(exchange, "GET"))
            return;
        String subscriptionId = query(exchange.getRequestURI()).get("subscriptionId");
        CodingBrowserAccess.Context context = null;
        boolean attached = false;
        try {
            context = browserContext(exchange);
            String clientId = context.clientId();
            if (subscriptionId == null) throw CodingHostException.invalidParams("subscriptionId required");
            var subscription = subscriptions.attach(clientId, subscriptionId);
            attached = true;
            if (subscription.type().equals("presentation"))
                context.requireCapability(CodingHostDispatcher.PRESENTATION_CAPABILITY);
            else if (subscription.type().equals("tool-preview")) context.requireCapability("preview-v1");
            else throw CodingHostException.unsupported("Browser observation source is unavailable");
            context.own("sse-" + subscriptionId, exchange::close);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                while (!subscription.closed()) {
                    context.requireValid();
                    var value = subscription.poll(2, TimeUnit.SECONDS);
                    context.requireValid();
                    if (value == null) {
                        output.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        continue;
                    }
                    byte[] payload = json.writeValueAsBytes(value);
                    output.write(("event: " + subscription.type() + "\ndata: ").getBytes(StandardCharsets.UTF_8));
                    output.write(payload);
                    output.write("\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            }
        } catch (CodingHostException denied) {
            if (!attached) browserFailure(exchange, null, denied);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException disconnected) {
            /* Observation only. */
        } finally {
            if (attached) {
                context.release("sse-" + subscriptionId);
                context.release("sub-" + subscriptionId);
            }
            exchange.close();
        }
    }

    public static InetAddress loopback(String bind) {
        String value = Objects.requireNonNull(bind, "bind must not be null").trim();
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        try {
            InetAddress[] addresses = InetAddress.getAllByName(value);
            for (InetAddress address : addresses) {
                if (!address.isLoopbackAddress()) {
                    throw new IllegalArgumentException("HOST_BIND_MUST_BE_LOOPBACK: " + value);
                }
            }
            if (addresses.length == 0) throw new IllegalArgumentException("HOST_BIND_MUST_BE_LOOPBACK: " + value);
            return addresses[0];
        } catch (IOException exception) {
            throw new IllegalArgumentException("HOST_BIND_INVALID: " + value, exception);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public Map<String, Object> recoveryReport() {
        return recoveryReport;
    }

    public void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    private void handleRpc(HttpExchange exchange) throws IOException {
        if (rejectBrowserOrigin(exchange) || rejectPath(exchange, "/v1/rpc") || rejectMethod(exchange, "POST")) return;
        if (!isJsonContentType(exchange)) {
            writeError(exchange, null, 415, -32600, "UNSUPPORTED_MEDIA_TYPE", "application/json is required");
            return;
        }
        JsonNode id = null;
        try (exchange) {
            byte[] body = readBounded(exchange);
            if (body.length > MAX_REQUEST_BYTES) {
                writeError(exchange, id, 413, -32600, "PAYLOAD_TOO_LARGE", "Request body is too large");
                return;
            }
            JsonNode request;
            try {
                request = json.readTree(body);
            } catch (IOException exception) {
                writeError(exchange, id, 200, -32700, "PARSE_ERROR", "Invalid JSON");
                return;
            }
            if (request == null
                    || !request.isObject()
                    || !request.path("jsonrpc").isTextual()
                    || !"2.0".equals(request.path("jsonrpc").textValue())) {
                writeError(
                        exchange,
                        request == null ? null : request.get("id"),
                        200,
                        -32600,
                        "INVALID_REQUEST",
                        "Invalid JSON-RPC request");
                return;
            }
            id = request.get("id");
            if (id != null
                    && !id.isNull()
                    && !id.isTextual()
                    && !id.isIntegralNumber()
                    && !id.isFloatingPointNumber()) {
                writeError(exchange, null, 200, -32600, "INVALID_REQUEST", "id must be string, number, or null");
                return;
            }
            if (!request.hasNonNull("method")
                    || !request.path("method").isTextual()
                    || request.path("method").textValue().isBlank()) {
                writeError(exchange, id, 200, -32600, "INVALID_REQUEST", "method is required");
                return;
            }
            String method = request.path("method").textValue();
            Semaphore permits = permitsFor(method, request.get("params"));
            try {
                if (!permits.tryAcquire(5, TimeUnit.SECONDS)) {
                    writeError(exchange, id, 503, -32000, "HOST_BUSY", "Host command capacity is exhausted");
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                writeError(exchange, id, 503, -32000, "HOST_BUSY", "Host command admission was interrupted");
                return;
            }
            try {
                String clientId = exchange.getRequestHeaders().getFirst("X-CA-Client-ID");
                if (method.equals("initialize")) {
                    Object result = dispatcher.dispatch(null, method, request.get("params"));
                    Map<String, Object> initialized = new LinkedHashMap<>();
                    if (result instanceof Map<?, ?> values) {
                        values.forEach((key, value) -> initialized.put(String.valueOf(key), value));
                    }
                    @SuppressWarnings("unchecked")
                    java.util.Set<String> optional = (java.util.Set<String>)
                            initialized.getOrDefault("optionalCapabilities", java.util.Set.of());
                    initialized.put("clientId", clients.initialize(optional));
                    initialized.put("hostIncarnation", incarnation);
                    initialized.put("displayName", displayName);
                    initialized.put("clientSource", "native");
                    if (optional.contains(CodingHostApprovals.CAPABILITY))
                        approvals.initialize((String) initialized.get("clientId"));
                    writeResult(exchange, id, initialized);
                } else {
                    if (method.equals("host/ping")) {
                        writeResult(exchange, id, dispatcher.dispatch(null, method, request.get("params")));
                    } else {
                        if (clientId == null || clientId.isBlank()) {
                            writeError(
                                    exchange, id, 401, -32000, "CLIENT_CONTEXT_REQUIRED", "X-CA-Client-ID is required");
                            return;
                        }
                        clients.require(clientId);
                        if (method.startsWith("product/")) clients.requireOptional(clientId, "coding-product-v1");
                        if (method.startsWith("presentation/"))
                            clients.requireOptional(clientId, CodingHostDispatcher.PRESENTATION_CAPABILITY);
                        if (method.equals("preview/subscribe")) clients.requireOptional(clientId, "preview-v1");
                        if (method.equals("approval/allowForSession")
                                || method.equals("approval/resetSession")
                                || method.equals("approval/status")
                                || method.equals("client/close"))
                            clients.requireOptional(clientId, CodingHostApprovals.CAPABILITY);
                        if (method.equals("client/renew")) {
                            approvals.renew(clientId);
                            subscriptions.refreshPresentations();
                        }
                        if (method.equals("client/close")) clients.remove(clientId);
                        Object result;
                        if (method.equals("client/info")) {
                            clients.requireOptional(clientId, CodingHostDispatcher.PRESENTATION_CAPABILITY);
                            var info = new LinkedHashMap<>(dispatcher.clientInfo(clients.capabilities(clientId)));
                            info.put("clientId", clientId);
                            result = info;
                        } else {
                            result = method.equals("host/configuration")
                                    ? configurationSummary(request.get("params"))
                                    : dispatchProduct(clientId, method, request.get("params"));
                        }
                        if (method.startsWith("approval/")) subscriptions.refreshPresentations();
                        writeResult(exchange, id, result);
                    }
                }
            } catch (CodingHostException exception) {
                writeError(
                        exchange,
                        id,
                        statusFor(exception),
                        exception.jsonRpcCode(),
                        exception.reasonCode(),
                        exception.getMessage());
            } catch (RuntimeException exception) {
                writeError(exchange, id, 500, -32603, "INTERNAL_ERROR", "Host command failed safely");
            } finally {
                permits.release();
            }
        }
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (rejectBrowserOrigin(exchange) || rejectPath(exchange, "/v1/events") || rejectMethod(exchange, "GET"))
            return;
        String subscriptionId = query(exchange.getRequestURI()).get("subscriptionId");
        if (subscriptionId == null || subscriptionId.isBlank()) {
            writeError(exchange, null, 400, -32602, "INVALID_PARAMS", "subscriptionId is required");
            return;
        }
        String clientId = exchange.getRequestHeaders().getFirst("X-CA-Client-ID");
        if (clientId == null || clientId.isBlank()) {
            writeError(exchange, null, 401, -32000, "CLIENT_CONTEXT_REQUIRED", "X-CA-Client-ID is required");
            return;
        }
        CodingHostSubscriptions.Subscription subscription;
        try {
            clients.require(clientId);
            subscription = subscriptions.attach(clientId, subscriptionId);
        } catch (CodingHostException exception) {
            writeError(
                    exchange,
                    null,
                    statusFor(exception),
                    exception.jsonRpcCode(),
                    exception.reasonCode(),
                    exception.getMessage());
            return;
        }
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                while (!subscription.closed()) {
                    if (subscription.type().equals("presentation")) {
                        try {
                            clients.requireObservation(clientId);
                        } catch (CodingHostException expired) {
                            break;
                        }
                    }
                    var value = subscription.poll(2, TimeUnit.SECONDS);
                    if (value == null) {
                        output.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        continue;
                    }
                    byte[] payload = json.writeValueAsBytes(value);
                    output.write(("event: " + subscription.type() + "\ndata: ").getBytes(StandardCharsets.UTF_8));
                    output.write(payload);
                    output.write("\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Disconnect closes the observation only; the Run continues.
        } finally {
            subscriptions.unsubscribe(clientId, subscriptionId);
            exchange.close();
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (rejectBrowserOrigin(exchange) || rejectPath(exchange, "/health") || rejectMethod(exchange, "GET")) return;
        try (exchange) {
            byte[] payload = json.writeValueAsBytes(
                    Map.of("status", "ok", "protocolVersion", "1", "host", "ca-host", "previewAvailable", false));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
        }
    }

    private static boolean isJsonContentType(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null) return false;
        int separator = contentType.indexOf(';');
        String mime = (separator >= 0 ? contentType.substring(0, separator) : contentType).trim();
        return mime.equalsIgnoreCase("application/json");
    }

    private boolean rejectPath(HttpExchange exchange, String expected) throws IOException {
        if (expected.equals(exchange.getRequestURI().getPath())) return false;
        writeError(exchange, null, 404, -32600, "PATH_NOT_FOUND", "Unsupported Host path");
        return true;
    }

    private boolean rejectBrowserOrigin(HttpExchange exchange) throws IOException {
        if (exchange.getRequestHeaders().getFirst("Origin") == null) return false;
        writeError(exchange, null, 403, -32000, "BROWSER_ORIGIN_REJECTED", "Browser origins are not accepted");
        return true;
    }

    private boolean rejectMethod(HttpExchange exchange, String expected) throws IOException {
        if (expected.equals(exchange.getRequestMethod())) return false;
        writeError(exchange, null, 405, -32600, "METHOD_NOT_ALLOWED", "Unsupported HTTP method");
        return true;
    }

    private byte[] readBounded(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        return body;
    }

    private void writeResult(HttpExchange exchange, JsonNode id, Object result) throws IOException {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) response.set("id", id);
        else response.putNull("id");
        response.set("result", json.valueToTree(result));
        write(exchange, 200, response);
    }

    private void writeError(HttpExchange exchange, JsonNode id, int status, int code, String reasonCode, String message)
            throws IOException {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) response.set("id", id);
        else response.putNull("id");
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message == null ? reasonCode : message);
        ObjectNode data = error.putObject("data");
        data.put("reasonCode", reasonCode);
        write(exchange, status, response);
    }

    private void write(HttpExchange exchange, int status, ObjectNode response) throws IOException {
        byte[] payload = json.writeValueAsBytes(response);
        if (payload.length > MAX_RESPONSE_BYTES) {
            payload = json.writeValueAsBytes(oversizedResponse(json, response));
            status = 200;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    public static ObjectNode oversizedResponse(ObjectMapper json, ObjectNode original) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        JsonNode requestId = original.get("id");
        response.set("id", requestId == null ? json.nullNode() : requestId);
        ObjectNode error = response.putObject("error");
        error.put("code", -32000);
        error.put("message", "Response is too large");
        error.putObject("data").put("reasonCode", "PAYLOAD_TOO_LARGE");
        return response;
    }

    private static int statusFor(CodingHostException exception) {
        return exception.reasonCode().contains("NOT_FOUND") ? 404 : exception.jsonRpcCode() == -32602 ? 400 : 409;
    }

    private static boolean controlMethod(String method) {
        return method.equals("client/renew")
                || method.equals("shell/get")
                || method.equals("shell/list")
                || method.equals("shell/cancel")
                || method.equals("run/cancel")
                || method.equals("interaction/respond")
                || method.startsWith("approval/")
                || method.equals("client/close")
                || method.startsWith("auth/");
    }

    private Map<String, Object> recoverSessions(
            io.haifa.agent.application.project.product.coding.CodingSessionService sessions,
            io.haifa.agent.runtime.api.AgentRuntime runtime) {
        List<String> settled = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int scanned = 0;
        boolean scanTruncated = false;
        try {
            var page = sessions.listSessionsForRecovery(projectId, 100);
            scanTruncated = page.hasMore();
            for (CodingSessionSummary summary : page.items()) {
                scanned++;
                Optional<AgentRunId> active = summary.activeRunId();
                if (active.isEmpty()) continue;
                try {
                    var reconciled = client.reconcile(summary.sessionId());
                    boolean stillActive = reconciled
                            .activeRun()
                            .map(run -> run.runId().equals(active.orElseThrow()))
                            .orElse(false);
                    boolean terminal = runtime.find(active.orElseThrow())
                            .filter(snapshot -> snapshot.status().isTerminal())
                            .isPresent();
                    if (!stillActive && terminal)
                        settled.add(active.orElseThrow().value());
                } catch (RuntimeException exception) {
                    errors.add(safeErrorCode(exception));
                }
            }
        } catch (RuntimeException exception) {
            errors.add(safeErrorCode(exception));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scannedSessions", scanned);
        report.put("scanLimit", 100);
        report.put("scanTruncated", scanTruncated);
        report.put("settledRunIds", settled);
        report.put("errors", errors);
        report.put(
                "note",
                "Existing product recovery semantics; no automatic replay of uncertain tool/shell side effects");
        return report;
    }

    private static String safeErrorCode(RuntimeException exception) {
        if (exception instanceof io.haifa.agent.application.project.product.ProjectProductException product) {
            return product.code();
        }
        if (exception instanceof io.haifa.agent.runtime.api.RuntimeContractException contract) {
            return contract.code().name();
        }
        return "RECOVERY_SCAN_FAILED";
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return values;
        for (String entry : raw.split("&")) {
            int separator = entry.indexOf('=');
            if (separator < 0) continue;
            values.put(
                    URLDecoder.decode(entry.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(entry.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            if (browserExpiry != null) browserExpiry.shutdownNow();
            if (browserAccess != null) browserAccess.close();
            productClients.values().forEach(io.haifa.coding.api.CodingClient::close);
            productClients.clear();
            server.stop(0);
        } finally {
            try {
                maintenance.close();
                subscriptions.closeAll();
                clients.close();
                approvals.close();
            } finally {
                executor.shutdownNow();
                try {
                    agent.close();
                } finally {
                    terminated.countDown();
                }
            }
        }
    }
}
