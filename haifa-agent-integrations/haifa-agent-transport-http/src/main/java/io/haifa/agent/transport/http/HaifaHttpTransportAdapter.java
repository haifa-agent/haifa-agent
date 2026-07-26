package io.haifa.agent.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.contract.interaction.InteractionResponseRequest;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RuntimeErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Framework-neutral reference HTTP/SSE adapter. A host owns sockets and deployment; this class owns only
 * protocol routing, validation, mapping and stream lifecycle.
 */
public final class HaifaHttpTransportAdapter {
    private static final Pattern RUN = Pattern.compile("^/v1/runs/([^/]+)$");
    private static final Pattern RESUME = Pattern.compile("^/v1/runs/([^/]+)/resume$");
    private static final Pattern INPUTS = Pattern.compile("^/v1/runs/([^/]+)/inputs$");
    private static final Pattern COMMANDS = Pattern.compile("^/v1/runs/([^/]+)/commands$");
    private static final Pattern PENDING = Pattern.compile("^/v1/runs/([^/]+)/interactions/pending$");
    private static final Pattern RESPONSE = Pattern.compile("^/v1/runs/([^/]+)/interactions/([^/]+)/responses$");
    private static final Pattern EVENTS = Pattern.compile("^/v1/runs/([^/]+)/events$");
    private static final Pattern STREAM = Pattern.compile("^/v1/runs/([^/]+)/events/stream$");

    private final AgentRuntime runtime;
    private final HttpCallerResolver callers;
    private final RunOperationAuthorizer authorizer;
    private final RuntimeCallerScope callerScope;
    private final ContractRuntimeMapper mapper;
    private final HttpJsonCodec json;
    private final HttpProblemMapper problems;
    private final HttpTransportConfiguration configuration;
    private final Supplier<String> correlationIds;

    public HaifaHttpTransportAdapter(
            AgentRuntime runtime,
            HttpCallerResolver callers,
            RunOperationAuthorizer authorizer,
            RuntimeCallerScope callerScope,
            RunEventCursorTokenCodec cursorCodec,
            ObjectMapper objectMapper,
            Clock clock,
            HttpTransportConfiguration configuration,
            Supplier<String> correlationIds) {
        this.runtime = Objects.requireNonNull(runtime);
        this.callers = Objects.requireNonNull(callers);
        this.authorizer = Objects.requireNonNull(authorizer);
        this.callerScope = Objects.requireNonNull(callerScope);
        this.mapper = new ContractRuntimeMapper(Objects.requireNonNull(cursorCodec));
        this.json = new HttpJsonCodec(Objects.requireNonNull(objectMapper));
        this.problems = new HttpProblemMapper(objectMapper, Objects.requireNonNull(clock));
        this.configuration = Objects.requireNonNull(configuration);
        this.correlationIds = Objects.requireNonNull(correlationIds);
    }

    public HttpTransportResponse handle(HttpTransportRequest request) {
        String correlationId = correlationId();
        try {
            validateCommon(request);
            if (request.method().equals("POST") && request.path().equals("/v1/runs")) {
                return start(request, correlationId);
            }
            Matcher match;
            if (request.method().equals("GET") && (match = RUN.matcher(request.path())).matches()) {
                return query(request, match.group(1), correlationId);
            }
            if (request.method().equals("POST") && (match = RESUME.matcher(request.path())).matches()) {
                return resume(request, match.group(1), correlationId);
            }
            if (request.method().equals("POST") && (match = INPUTS.matcher(request.path())).matches()) {
                return input(request, match.group(1), correlationId);
            }
            if (request.method().equals("POST") && (match = COMMANDS.matcher(request.path())).matches()) {
                return command(request, match.group(1), correlationId);
            }
            if (request.method().equals("GET") && (match = PENDING.matcher(request.path())).matches()) {
                return pending(request, match.group(1), correlationId);
            }
            if (request.method().equals("POST") && (match = RESPONSE.matcher(request.path())).matches()) {
                return respond(request, match.group(1), match.group(2), correlationId);
            }
            if (request.method().equals("GET") && (match = EVENTS.matcher(request.path())).matches()) {
                return events(request, match.group(1), correlationId);
            }
            throw new TransportFailure(RuntimeErrorCode.RUN_NOT_FOUND, 404, "No HTTP operation matches the request");
        } catch (Throwable failure) {
            return problems.map(failure, correlationId);
        }
    }

    public SseOpenResult openEventStream(HttpTransportRequest request) {
        String correlationId = correlationId();
        try {
            validateCommon(request);
            Matcher match = STREAM.matcher(request.path());
            if (!request.method().equals("GET") || !match.matches()) {
                throw new TransportFailure(RuntimeErrorCode.RUN_NOT_FOUND, 404, "No SSE operation matches the request");
            }
            if (!request.header("Accept").orElse("").contains("text/event-stream")) {
                throw new TransportFailure(
                        RuntimeErrorCode.CONTRACT_VERSION_UNSUPPORTED, 406, "Accept must include text/event-stream");
            }
            AgentRunId runId = new AgentRunId(match.group(1));
            TrustedCallerContext caller = authenticate(request);
            authorize(caller, RunOperation.SUBSCRIBE_EVENTS, runId.value(), Optional.empty());
            RunEventCursor cursor = decodeStreamCursor(request, runId);
            HttpSseSession session =
                    new HttpSseSession(runId, caller, authorizer, mapper, json, configuration.sseQueueCapacity());
            var subscription = callerScope.call(caller, () -> runtime.subscribe(runId, cursor, session::onEvent));
            session.attach(subscription);
            return SseOpenResult.opened(session);
        } catch (Throwable failure) {
            return SseOpenResult.failed(problems.map(failure, correlationId));
        }
    }

    private HttpTransportResponse start(HttpTransportRequest request, String correlationId) {
        requireJson(request);
        TrustedCallerContext caller = authenticate(request);
        authorize(caller, RunOperation.START, null, Optional.empty());
        var external = json.start(request.body(), request.header("Idempotency-Key"));
        var snapshot = callerScope.call(caller, () -> runtime.start(mapper.start(external)));
        var view = runView(caller, new AgentRunViewSnapshot(new AgentSessionId(external.sessionId()), snapshot));
        return response(202, json.write(view), correlationId);
    }

    private HttpTransportResponse query(HttpTransportRequest request, String pathRunId, String correlationId) {
        TrustedCallerContext caller = authenticate(request);
        authorize(caller, RunOperation.QUERY, pathRunId, Optional.empty());
        AgentRunViewSnapshot view = requireView(caller, new AgentRunId(pathRunId));
        return response(200, json.write(runView(caller, view)), correlationId);
    }

    private HttpTransportResponse resume(HttpTransportRequest request, String pathRunId, String correlationId) {
        requireJson(request);
        TrustedCallerContext caller = authenticate(request);
        authorize(caller, RunOperation.RESUME, pathRunId, Optional.empty());
        var body = json.resume(request.body(), request.header("Idempotency-Key"), ifMatch(request.header("If-Match")));
        requireIdentity(pathRunId, body.runId(), RuntimeErrorCode.RUN_STATE_CONFLICT);
        var snapshot = callerScope.call(
                caller,
                () -> runtime.resume(new ResumeAgentRunRequest(
                        body.idempotencyKey().value(),
                        new AgentRunId(pathRunId),
                        Optional.empty(),
                        body.expectedRunVersion(),
                        List.of())));
        return response(
                202,
                json.write(runView(
                        caller,
                        new AgentRunViewSnapshot(
                                requireView(caller, snapshot.runId()).sessionId(), snapshot))),
                correlationId);
    }

    private HttpTransportResponse input(HttpTransportRequest request, String pathRunId, String correlationId) {
        requireJson(request);
        TrustedCallerContext caller = authenticate(request);
        authorize(caller, RunOperation.SUBMIT_INPUT, pathRunId, Optional.empty());
        var external =
                json.input(request.body(), request.header("Idempotency-Key"), ifMatch(request.header("If-Match")));
        requireIdentity(pathRunId, external.runId(), RuntimeErrorCode.RUN_STATE_CONFLICT);
        checkRunVersion(caller, new AgentRunId(pathRunId), external.expectedRunVersion());
        var receipt = callerScope.call(caller, () -> runtime.submitInput(mapper.input(external)));
        var contract = new io.haifa.agent.contract.run.RunInputReceipt(
                io.haifa.agent.contract.common.ApiVersion.CURRENT,
                receipt.inputId().value(),
                receipt.runId().value(),
                receipt.status().name(),
                receipt.acceptedAt(),
                receipt.appliedAt(),
                receipt.attemptId(),
                receipt.iteration(),
                receipt.reasonCode());
        return response(202, json.write(contract), correlationId);
    }

    private HttpTransportResponse command(HttpTransportRequest request, String pathRunId, String correlationId) {
        requireJson(request);
        TrustedCallerContext caller = authenticate(request);
        authorize(caller, RunOperation.COMMAND, pathRunId, Optional.empty());
        var external =
                json.command(request.body(), request.header("Idempotency-Key"), ifMatch(request.header("If-Match")));
        requireIdentity(pathRunId, external.runId(), RuntimeErrorCode.RUN_STATE_CONFLICT);
        var result = callerScope.call(caller, () -> runtime.command(mapper.command(external)));
        var contract = new io.haifa.agent.contract.run.RuntimeCommandReceipt(
                io.haifa.agent.contract.common.ApiVersion.CURRENT,
                result.command().commandId().value(),
                result.command().runId().value(),
                result.command().type().name(),
                result.status().name(),
                result.snapshot().version());
        return response(202, json.write(contract), correlationId);
    }

    private HttpTransportResponse pending(HttpTransportRequest request, String pathRunId, String correlationId) {
        TrustedCallerContext caller = authenticate(request);
        authorize(caller, RunOperation.QUERY_INTERACTION, pathRunId, Optional.empty());
        var interaction = callerScope.call(caller, () -> runtime.pendingInteraction(new AgentRunId(pathRunId)));
        if (interaction.isEmpty()) return response(204, new byte[0], correlationId);
        return response(200, json.write(mapper.interaction(interaction.orElseThrow())), correlationId);
    }

    private HttpTransportResponse respond(
            HttpTransportRequest request, String pathRunId, String pathRequestId, String correlationId) {
        requireJson(request);
        TrustedCallerContext caller = authenticate(request);
        authorize(caller, RunOperation.RESPOND_INTERACTION, pathRunId, Optional.of(pathRequestId));
        InteractionResponseRequest external =
                json.response(request.body(), request.header("Idempotency-Key"), ifMatch(request.header("If-Match")));
        requireIdentity(pathRunId, external.runId(), RuntimeErrorCode.INTERACTION_NOT_FOUND);
        requireIdentity(pathRequestId, external.requestId(), RuntimeErrorCode.INTERACTION_NOT_FOUND);
        var receipt = callerScope.call(caller, () -> runtime.respond(mapper.interaction(external)));
        var contract = new io.haifa.agent.contract.interaction.InteractionResponseReceipt(
                io.haifa.agent.contract.common.ApiVersion.CURRENT,
                receipt.responseId().value(),
                receipt.requestId().value(),
                receipt.runId().value(),
                receipt.status().name(),
                receipt.interactionState().name(),
                receipt.revision(),
                receipt.runVersion());
        return response(202, json.write(contract), correlationId);
    }

    private HttpTransportResponse events(HttpTransportRequest request, String pathRunId, String correlationId) {
        TrustedCallerContext caller = authenticate(request);
        authorize(caller, RunOperation.READ_EVENTS, pathRunId, Optional.empty());
        AgentRunId runId = new AgentRunId(pathRunId);
        RunEventCursor cursor = request.query("cursor")
                .map(value -> mapper.cursors().decode(runId, value))
                .orElseGet(() -> RunEventCursor.beforeFirst(runId));
        int limit = request.query("limit")
                .map(HaifaHttpTransportAdapter::positiveInteger)
                .orElse(configuration.defaultEventPageSize());
        if (limit > configuration.maximumEventPageSize()) {
            throw new IllegalArgumentException("event page limit exceeds configured maximum");
        }
        var page = callerScope.call(caller, () -> runtime.events(runId, cursor, limit));
        return response(200, json.write(mapper.eventPage(page)), correlationId);
    }

    private io.haifa.agent.contract.run.RunView runView(TrustedCallerContext caller, AgentRunViewSnapshot view) {
        AgentRunId runId = view.snapshot().runId();
        var page = callerScope.call(caller, () -> runtime.events(runId, RunEventCursor.beforeFirst(runId), 1));
        Optional<String> pendingInteractionId = callerScope
                .call(caller, () -> runtime.pendingInteraction(runId))
                .map(interaction -> interaction.requestId().value());
        return mapper.runView(
                view,
                pendingInteractionId,
                Optional.of(mapper.cursors().encode(RunEventCursor.beforeFirst(runId))),
                Optional.of(mapper.cursors().encode(page.headCursor())));
    }

    private AgentRunViewSnapshot requireView(TrustedCallerContext caller, AgentRunId runId) {
        return callerScope
                .call(caller, () -> runtime.view(runId))
                .orElseThrow(() -> new TransportFailure(
                        RuntimeErrorCode.RUN_NOT_FOUND, 404, "The run does not exist or is not visible"));
    }

    private void checkRunVersion(TrustedCallerContext caller, AgentRunId runId, OptionalLong expected) {
        if (expected.isEmpty()) return;
        long current = requireView(caller, runId).snapshot().version();
        if (current != expected.getAsLong()) {
            throw new TransportFailure(RuntimeErrorCode.RUN_VERSION_CONFLICT, 412, "The expected Run version is stale");
        }
    }

    private RunEventCursor decodeStreamCursor(HttpTransportRequest request, AgentRunId runId) {
        Optional<String> query = request.query("cursor");
        Optional<String> header = request.header("Last-Event-ID");
        if (query.isPresent() && header.isPresent() && !query.equals(header)) {
            throw new TransportFailure(RuntimeErrorCode.CURSOR_INVALID, 409, "Cursor sources differ");
        }
        return query.or(() -> header)
                .map(value -> mapper.cursors().decode(runId, value))
                .orElseGet(() -> RunEventCursor.beforeFirst(runId));
    }

    private TrustedCallerContext authenticate(HttpTransportRequest request) {
        return Objects.requireNonNull(callers.resolve(request.metadata()), "caller resolver returned null");
    }

    private void authorize(
            TrustedCallerContext caller, RunOperation operation, String runId, Optional<String> interactionId) {
        authorizer.authorize(caller, operation, Optional.ofNullable(runId), interactionId);
    }

    private void validateCommon(HttpTransportRequest request) {
        if (request.body().length > configuration.maximumRequestBytes()) {
            throw new TransportFailure(RuntimeErrorCode.PAYLOAD_TOO_LARGE, 413, "Request payload is too large");
        }
        request.header("X-Haifa-Api-Version").ifPresent(version -> {
            if (!version.equals(configuration.apiVersion())) {
                throw new TransportFailure(
                        RuntimeErrorCode.CONTRACT_VERSION_UNSUPPORTED, 400, "API version is unsupported");
            }
        });
    }

    private static void requireJson(HttpTransportRequest request) {
        String contentType = request.header("Content-Type").orElse("");
        if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            throw new TransportFailure(
                    RuntimeErrorCode.CONTRACT_VERSION_UNSUPPORTED, 415, "Content-Type must be application/json");
        }
    }

    private HttpTransportResponse response(int status, byte[] body, String correlationId) {
        return new HttpTransportResponse(
                status,
                Map.of(
                        "Content-Type",
                        "application/json",
                        "X-Haifa-Api-Version",
                        configuration.apiVersion(),
                        "X-Correlation-Id",
                        correlationId),
                body);
    }

    private String correlationId() {
        String value = Objects.requireNonNull(correlationIds.get(), "correlation ID supplier returned null")
                .trim();
        if (value.isEmpty() || value.length() > 128 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalStateException("correlation ID supplier returned an invalid value");
        }
        return value;
    }

    private static OptionalLong ifMatch(Optional<String> value) {
        if (value.isEmpty()) return OptionalLong.empty();
        String raw = value.orElseThrow().trim();
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            raw = raw.substring(1, raw.length() - 1);
        }
        try {
            long parsed = Long.parseLong(raw);
            if (parsed < 0) throw new NumberFormatException();
            return OptionalLong.of(parsed);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must contain a non-negative version", exception);
        }
    }

    private static int positiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("limit must be a positive integer", exception);
        }
    }

    private static void requireIdentity(String path, String body, RuntimeErrorCode code) {
        if (!path.equals(body)) throw new TransportFailure(code, 409, "URL and body identity differ");
    }
}
