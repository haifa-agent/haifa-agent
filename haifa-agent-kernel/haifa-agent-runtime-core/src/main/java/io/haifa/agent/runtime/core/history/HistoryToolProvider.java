package io.haifa.agent.runtime.core.history;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.*;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.*;
import io.haifa.agent.runtime.core.bootstrap.RunAccessValidator;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.tool.ToolCallViewProjector;
import io.haifa.agent.tool.api.*;
import java.time.Duration;
import java.util.*;

/** Session-scoped, read-only history tools. References are identities, never authorization grants. */
public final class HistoryToolProvider implements ToolProvider {
    public static final ToolProviderId ID = new ToolProviderId("haifa-runtime-history");
    private static final String SOURCES = "historySources";
    private final RuntimePersistencePorts ports;
    private final RunAccessValidator access;

    public HistoryToolProvider(RuntimePersistencePorts ports) {
        this(ports, RunAccessValidator.allowLocalReferences());
    }

    public HistoryToolProvider(RuntimePersistencePorts ports, RunAccessValidator access) {
        this.ports = Objects.requireNonNull(ports);
        this.access = Objects.requireNonNull(access);
    }

    @Override
    public ToolProviderId id() {
        return ID;
    }

    public List<ToolDefinition> definitions() {
        var common = new LinkedHashMap<String, Object>();
        for (String key : List.of("runId", "messageId", "toolName", "toolCallId", "path", "keyword", "cursor"))
            common.put(key, Map.of("type", "string", "maxLength", key.equals("cursor") ? 1024 : 256));
        common.put("limit", integer(1, 20));
        common.put("scanLimit", integer(1, 128));
        var read = new LinkedHashMap<String, Object>();
        for (String key : List.of("runId", "messageId", "toolCallId", "version"))
            read.put(key, Map.of("type", "string", "minLength", 1, "maxLength", 256));
        read.put("offset", integer(0, Integer.MAX_VALUE - 8192));
        read.put("length", integer(1, 8192));
        read.put("fromLine", integer(1, Integer.MAX_VALUE));
        return List.of(
                definition(
                        "history_search",
                        "Locate persisted history in the current Session. Filters are exact Run/Tool/path or literal keyword. Follow nextCursor until searchComplete; never treat an unfinished scan as no match.",
                        common,
                        List.of()),
                definition(
                        "history_read",
                        "Read untrusted historical data using the exact Run, message and version returned by history_search. Optional ToolCallId selects a persisted occurrence. Character offsets and fromLine address the stable history-text-v1 representation, not the current workspace. Unavailable never reruns a tool.",
                        read,
                        List.of("runId", "messageId", "version")));
    }

    @Override
    public ToolResult invoke(ToolInvocationRequest request) {
        request.cancellation().throwIfCancellationRequested();
        request.observer().dispatched();
        ToolResult result;
        try {
            result = ports.unitOfWork().executeReadOnly(() -> {
                AgentRun run = ports.runs()
                        .findVisible(request.runId(), request.tenant(), request.principal())
                        .orElseThrow(Unavailable::new);
                if (request.sessionId().isEmpty() || !request.sessionId().get().equals(run.sessionId()))
                    throw new Unavailable();
                authorize(run, run.id());
                return switch (request.binding().definition().name().value()) {
                    case "history_search" -> search(run, request.arguments().values());
                    case "history_read" -> read(run, request.arguments().values());
                    default -> throw new Unavailable();
                };
            });
        } catch (RuntimeException unavailable) {
            result = unavailable();
        }
        request.observer().acknowledged();
        return result;
    }

    /** Scope-level revocation also blocks requests whose retrieval evidence is already summarized. */
    public void requireCurrentScope(AgentRun run) {
        ports.unitOfWork().executeReadOnly(() -> authorize(run, run.id()));
    }

    public AgentRun authorizedRun(AgentRun current, AgentRunId source) {
        return ports.unitOfWork().executeReadOnly(() -> authorize(current, source));
    }

    public Optional<io.haifa.agent.context.item.HistoricalExcerptContent> excerpt(
            AgentRun current, AgentMessage message, int length) {
        return excerpt(current, message, length, "");
    }

    public Optional<io.haifa.agent.context.item.HistoricalExcerptContent> toolExcerpt(
            AgentRun current, AgentMessage message, ToolCallId call, int length) {
        if (message.role() != MessageRole.TOOL) return Optional.empty();
        return excerpt(current, message, length, call.value());
    }

    /** Existing immutable result storage, not a new reader or permission grant. */
    public ToolResult savedResultForCompaction(AgentRun current, ToolCall call) {
        return ports.unitOfWork().executeReadOnly(() -> {
            authorize(current, call.runId());
            var result = sanitize(current, call).result().orElseThrow(Unavailable::new);
            var asset = result.assets().stream()
                    .filter(ref ->
                            ref.assetId().equals("tool-result:" + call.id().value())
                                    && ref.mimeType().equals("application/vnd.haifa.tool-result"))
                    .findFirst();
            if (asset.isEmpty()) return result;
            return ports.toolResultAssets()
                    .load(asset.orElseThrow())
                    .orElseThrow(() -> new io.haifa.agent.context.api.ContextBuildException(
                            io.haifa.agent.context.api.ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                            "saved compaction result is unavailable"));
        });
    }

    private Optional<io.haifa.agent.context.item.HistoricalExcerptContent> excerpt(
            AgentRun current, AgentMessage message, int length, String selectedCall) {
        return ports.unitOfWork().executeReadOnly(() -> {
            try {
                Source value =
                        source(current, message.runId().orElseThrow(Unavailable::new), message.id(), selectedCall);
                if (message.contents().stream()
                        .anyMatch(part -> !(part instanceof TextPart)
                                && !(part instanceof ToolResultPart)
                                && !(part instanceof io.haifa.agent.core.content.ToolCallPart)))
                    return Optional.empty();
                value = materialize(value);
                var window = value.window(0, length, 1);
                if (window.incomplete() || window.value().isBlank()) return Optional.empty();
                return Optional.of(new io.haifa.agent.context.item.HistoricalExcerptContent(
                        value.runId(),
                        message.id(),
                        value.version(),
                        value.calls().size() == 1
                                ? Optional.of(value.calls().getFirst().id())
                                : Optional.empty(),
                        0,
                        length,
                        window.value(),
                        value.results().stream().anyMatch(ToolResult::truncated),
                        window.more()));
            } catch (Unavailable unavailable) {
                return Optional.empty();
            }
        });
    }

    public Optional<io.haifa.agent.context.item.HistoricalExcerptContent> refresh(
            AgentRunId current, io.haifa.agent.context.item.HistoricalExcerptContent excerpt) {
        return ports.unitOfWork().executeReadOnly(() -> {
            try {
                var run = ports.runs().find(current).orElseThrow(Unavailable::new);
                var value = source(
                        run,
                        excerpt.runId(),
                        excerpt.messageId(),
                        excerpt.toolCallId().map(ToolCallId::value).orElse(""));
                if (!value.version().equals(excerpt.version())) return Optional.empty();
                var window = materialize(value).window(excerpt.offset(), excerpt.length(), 1);
                if (window.incomplete()) return Optional.empty();
                return Optional.of(new io.haifa.agent.context.item.HistoricalExcerptContent(
                        excerpt.runId(),
                        excerpt.messageId(),
                        excerpt.version(),
                        excerpt.toolCallId(),
                        excerpt.offset(),
                        excerpt.length(),
                        window.value(),
                        value.results().stream().anyMatch(ToolResult::truncated),
                        window.more()));
            } catch (Unavailable unavailable) {
                return Optional.empty();
            }
        });
    }

    public boolean validPlan(AgentRunId current, AgentRunId owner, String revision) {
        return ports.unitOfWork().executeReadOnly(() -> {
            var run = ports.runs().find(current).orElseThrow(Unavailable::new);
            authorize(run, owner);
            return ports.state()
                    .plan(owner)
                    .filter(plan -> Long.toString(plan.revision()).equals(revision))
                    .isPresent();
        });
    }

    public boolean validSummary(AgentRunId current, String id, long version) {
        return ports.unitOfWork().executeReadOnly(() -> {
            var run = ports.runs().find(current).orElseThrow(Unavailable::new);
            authorize(run, run.id());
            var summary = ports.conversationSummaries()
                    .latestValid(run.sessionId())
                    .filter(value ->
                            value.id().value().equals(id) && value.version().value() == version);
            if (summary.isEmpty()
                    || summary.get().sourceMessageIds().isEmpty()
                    || summary.get().sourceMessageIds().size() > 64) return false;
            var owners = new HashSet<AgentRunId>();
            for (var source : summary.get().sourceMessageIds()) {
                var message = ports.state().message(source).orElseThrow(Unavailable::new);
                if (!message.sessionId().equals(run.sessionId()) || message.status() == MessageStatus.REDACTED)
                    return false;
                owners.add(message.runId().orElseThrow(Unavailable::new));
            }
            try {
                owners.forEach(owner -> authorize(run, owner));
                return true;
            } catch (Unavailable denied) {
                return false;
            }
        });
    }

    private AgentRun authorize(AgentRun current, AgentRunId id) {
        AgentRun source = ports.runs()
                .findVisible(id, current.tenant(), current.principal())
                .orElseThrow(Unavailable::new);
        if (!source.sessionId().equals(current.sessionId()) || !source.project().equals(current.project()))
            throw new Unavailable();
        access.validate(
                new RuntimeCallerContext(current.tenant(), current.principal()), source.sessionId(), source.project());
        return source;
    }

    private ToolResult search(AgentRun run, Map<String, Object> args) {
        String runFilter = text(args, "runId");
        if (!runFilter.isEmpty()) authorize(run, new AgentRunId(runFilter));
        String query = digest(Map.of(
                "run",
                run.id().value(),
                "session",
                run.sessionId().value(),
                "filters",
                Map.of(
                        "messageId",
                        text(args, "messageId"),
                        "runId",
                        runFilter,
                        "toolName",
                        text(args, "toolName"),
                        "toolCallId",
                        text(args, "toolCallId"),
                        "path",
                        text(args, "path"),
                        "keyword",
                        text(args, "keyword"))));
        long head = ports.state()
                .latestMessageCursor(run.sessionId())
                .orElse(MessageCursor.BEFORE_FIRST)
                .value();
        long after = 0;
        int offset = 0;
        String cursor = text(args, "cursor");
        if (!cursor.isEmpty()) {
            String[] bits = cursor.split(":", -1);
            if (bits.length != 5 || !bits[0].equals("h1") || !bits[4].equals(query)) throw new Unavailable();
            after = Long.parseLong(bits[1]);
            long observed = Long.parseLong(bits[2]);
            offset = Integer.parseInt(bits[3]);
            if (after < 0 || observed < after || observed > head || offset < 0) throw new Unavailable();
            head = observed;
        }
        int limit = number(args, "limit", 10, 1, 20), scans = number(args, "scanLimit", 128, 1, 128);
        List<AgentMessage> page = ports.state().messagesAfter(run.sessionId(), new MessageCursor(after), scans);
        List<Map<String, Object>> hits = new ArrayList<>();
        List<Map<String, Object>> refs = new ArrayList<>();
        boolean partialSource = false;
        int responseCharacters = 512;
        String keyword = text(args, "keyword");
        var literal = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(keyword),
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
        for (AgentMessage message : page) {
            if (message.sequence() > head) break;
            if ((!text(args, "messageId").isEmpty()
                            && !text(args, "messageId").equals(message.id().value()))
                    || (!runFilter.isEmpty()
                            && !message.runId()
                                    .map(AgentRunId::value)
                                    .orElse("")
                                    .equals(runFilter))) {
                after = message.sequence();
                offset = 0;
                continue;
            }
            Source source;
            try {
                source = source(
                        run, message.runId().orElseThrow(Unavailable::new), message.id(), text(args, "toolCallId"));
            } catch (Unavailable denied) {
                after = message.sequence();
                offset = 0;
                continue;
            }
            boolean matches = (text(args, "messageId").isEmpty()
                            || text(args, "messageId")
                                    .equals(source.message().id().value()))
                    && (runFilter.isEmpty() || runFilter.equals(source.runId().value()))
                    && (text(args, "toolName").isEmpty()
                            || source.calls().stream()
                                    .anyMatch(call -> call.toolName().equals(text(args, "toolName"))))
                    && (text(args, "path").isEmpty() || source.paths().contains(text(args, "path")));
            if (!matches) {
                after = message.sequence();
                offset = 0;
                continue;
            }
            source = materialize(source);
            var window = source.window(offset, 4096 + Math.max(0, keyword.length() - 1), 1);
            if (window.incomplete()) throw new Unavailable();
            var matcher = literal.matcher(window.value());
            int found = keyword.isEmpty() ? 0 : (matcher.find() ? matcher.start() : -1);
            if (found >= 0) {
                var ref = source.reference();
                var hit = new LinkedHashMap<String, Object>(ref);
                hit.put(
                        "toolNames",
                        source.calls().stream().map(ToolCall::toolName).toList());
                hit.put(
                        "toolCallIds",
                        source.calls().stream().map(call -> call.id().value()).toList());
                hit.put("paths", source.paths());
                hit.put(
                        "excerpt",
                        window.value().substring(found, Math.min(window.value().length(), found + 240)));
                hit.put("matchOffset", (long) offset + found);
                int contribution = hit.toString().length() + ref.toString().length();
                if (responseCharacters + contribution > 12000) {
                    if (hits.isEmpty()) throw new Unavailable();
                    partialSource = true;
                    break;
                }
                responseCharacters += contribution;
                hits.add(Map.copyOf(hit));
                refs.add(ref);
            } else if (window.more()) {
                // A large single source also has a resumable scan; never silently skip its unsearched suffix.
                after = message.sequence() - 1;
                offset = Math.addExact(offset, 4096);
                partialSource = true;
                break;
            }
            after = message.sequence();
            offset = 0;
            if (hits.size() >= limit) break;
        }
        boolean complete =
                !partialSource && (after >= head || page.isEmpty() || (page.size() < scans && hits.size() < limit));
        return result(Map.of(
                "entries",
                hits,
                "searchComplete",
                complete,
                "nextCursor",
                complete ? "" : "h1:" + after + ":" + head + ":" + offset + ":" + query,
                "securityLabel",
                "untrusted-history",
                SOURCES,
                refs));
    }

    private ToolResult read(AgentRun run, Map<String, Object> args) {
        var source = source(
                run,
                new AgentRunId(required(args, "runId")),
                new AgentMessageId(required(args, "messageId")),
                text(args, "toolCallId"));
        if (!source.version().equals(required(args, "version"))) throw new Unavailable();
        source = materialize(source);
        var window = source.window(
                number(args, "offset", 0, 0, Integer.MAX_VALUE - 8192),
                number(args, "length", 4096, 1, 8192),
                number(args, "fromLine", 1, 1, Integer.MAX_VALUE));
        if (window.incomplete()) throw new Unavailable();
        Map<String, Object> data = new LinkedHashMap<>(source.reference());
        data.put("content", window.value());
        data.put("truncated", window.more());
        data.put("nextOffset", window.nextOffset());
        data.put("fromLine", number(args, "fromLine", 1, 1, Integer.MAX_VALUE));
        data.put("representation", "history-text-v1");
        data.put("securityLabel", "untrusted-history");
        data.put(SOURCES, List.of(source.reference()));
        return result(data);
    }

    private Source source(AgentRun current, AgentRunId runId, AgentMessageId id, String selectedCall) {
        authorize(current, runId); // Ownership is checked before reading the body or referenced payload.
        AgentMessage message = ports.state().message(id).orElseThrow(Unavailable::new);
        if (!List.of(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL).contains(message.role())
                || !message.runId().equals(Optional.of(runId))
                || !message.sessionId().equals(current.sessionId())
                || message.status() != MessageStatus.COMPLETED
                || !(message.visibility() == MessageVisibility.USER_VISIBLE
                        || message.visibility() == MessageVisibility.AGENT_VISIBLE)) throw new Unavailable();
        List<ToolResultPart> parts = message.contents().stream()
                .filter(ToolResultPart.class::isInstance)
                .map(ToolResultPart.class::cast)
                .filter(part ->
                        selectedCall.isEmpty() || part.toolCallId().value().equals(selectedCall))
                .toList();
        if (!selectedCall.isEmpty() && parts.isEmpty()) throw new Unavailable();
        Set<ToolCallId> ids =
                parts.stream().map(ToolResultPart::toolCallId).collect(java.util.stream.Collectors.toSet());
        List<ToolCall> calls = ids.isEmpty() ? List.of() : ports.state().toolCallsByIds(Map.of(runId, ids));
        if (calls.size() != ids.size()) throw new Unavailable();
        for (ToolCall call : calls) {
            if (!call.runId().equals(runId)
                    || call.result().isEmpty()
                    || parts.stream()
                            .noneMatch(part -> part.toolCallId().equals(call.id())
                                    && part.providerCorrelationId().equals(call.providerCorrelationId())))
                throw new Unavailable();
            // Retrieval results are not recursively searchable/readable as original historical evidence.
            if (call.toolName().equals("history_read") || call.toolName().equals("history_search"))
                throw new Unavailable();
        }
        calls = calls.stream()
                .sorted(Comparator.comparing(call -> call.id().value()))
                .toList();
        List<ToolResult> results = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (ToolCall call : calls) {
            ToolResult value = call.result().orElseThrow();
            results.add(value);
            for (String key :
                    List.of("path", "file", "filePath", "targetPath", "sourcePath", "outputPath", "artifactPath")) {
                Object path = call.arguments().values().get(key);
                if (path instanceof String text && text.length() <= 256)
                    paths.add(ToolCallViewProjector.redactText(text));
            }
            value.artifacts().forEach(artifact -> {
                paths.add(ToolCallViewProjector.redactText(artifact.title()));
                paths.add(ToolCallViewProjector.redactText(artifact.artifactId()));
            });
        }
        String version = digest(Map.of(
                "message",
                id.value(),
                "sequence",
                message.sequence(),
                "run",
                runId.value(),
                "occurrences",
                calls.stream()
                        .map(call -> Map.of("id", call.id().value(), "version", call.version()))
                        .toList()));
        return new Source(message, calls, results, List.copyOf(new TreeSet<>(paths)), version);
    }

    private Source materialize(Source source) {
        List<ToolResult> results = new ArrayList<>();
        for (ToolCall call : source.calls()) {
            ToolResult value = call.result().orElseThrow(Unavailable::new);
            var asset = value.assets().stream()
                    .filter(ref ->
                            ref.assetId().equals("tool-result:" + call.id().value())
                                    && ref.mimeType().equals("application/vnd.haifa.tool-result"))
                    .findFirst();
            if (asset.isPresent())
                value = ports.toolResultAssets().load(asset.get()).orElseThrow(Unavailable::new);
            results.add(value);
        }
        return new Source(source.message(), source.calls(), results, source.paths(), source.version());
    }

    private record Source(
            AgentMessage message, List<ToolCall> calls, List<ToolResult> results, List<String> paths, String version) {
        AgentRunId runId() {
            return message.runId().orElseThrow();
        }

        Map<String, Object> reference() {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("runId", runId().value());
            ref.put("messageId", message.id().value());
            ref.put("version", version);
            ref.put("sourceKind", calls.isEmpty() ? "message" : "tool-result");
            ref.put("sourceTruncated", results.stream().anyMatch(ToolResult::truncated));
            if (calls.size() == 1) ref.put("toolCallId", calls.getFirst().id().value());
            return Map.copyOf(ref);
        }

        HistoryTextWindow window(int offset, int length, int fromLine) {
            var writer = new HistoryTextWindow(offset, length, fromLine);
            for (var part : message.contents()) if (part instanceof TextPart text) writer.text(text.text());
            for (ToolResult value : results) {
                writer.text(value.summary());
                writer.value(value.structuredData(), 0);
            }
            return writer;
        }
    }

    public ToolCall sanitize(AgentRunId current, ToolCall call) {
        if (call == null
                || !(call.toolName().equals("history_read") || call.toolName().equals("history_search"))) return call;
        return sanitize(ports.runs().find(current).orElseThrow(Unavailable::new), call);
    }

    public boolean unavailableRead(ToolCall call) {
        return call != null
                && (call.toolName().equals("history_search") || call.toolName().equals("history_read"))
                && owned(call)
                && call.result()
                        .map(result ->
                                "UNAVAILABLE".equals(result.structuredData().get("status")))
                        .orElse(false);
    }

    private boolean owned(ToolCall call) {
        return ports.runs()
                .find(call.runId())
                .flatMap(run -> ports.state().configuration(run.configurationSnapshot()))
                .map(config -> config.toolBindings().stream()
                        .anyMatch(binding -> binding.definition().providerId().equals(ID)
                                && binding.alias().value().equals(call.toolName())))
                .orElse(false);
    }

    /** Rechecks persisted retrieval evidence before it can be sent to a model again. */
    public ToolCall sanitize(AgentRun current, ToolCall call) {
        if (call == null
                || !(call.toolName().equals("history_read") || call.toolName().equals("history_search"))
                || call.result().isEmpty()) return call;
        if (!owned(call)) return call;
        ToolResult original = call.result().orElseThrow();
        try {
            ports.unitOfWork().executeReadOnly(() -> {
                authorize(current, current.id());
                if (!(original.structuredData().get(SOURCES) instanceof List<?> refs)) throw new Unavailable();
                for (Object value : refs) {
                    if (!(value instanceof Map<?, ?> ref)) throw new Unavailable();
                    var source = source(
                            current,
                            new AgentRunId((String) ref.get("runId")),
                            new AgentMessageId((String) ref.get("messageId")),
                            (ref.get("toolCallId") instanceof String selected ? selected : ""));
                    if (!source.version().equals(ref.get("version"))) throw new Unavailable();
                    materialize(source);
                }
                return true;
            });
            return call;
        } catch (RuntimeException revoked) {
            var s = call.persistenceSnapshot();
            return ToolCall.reconstitute(new ToolCallPersistenceSnapshot(
                    s.schemaVersion(),
                    s.id(),
                    s.runId(),
                    s.stepId(),
                    s.providerCorrelationId(),
                    s.idempotencyKey(),
                    s.toolName(),
                    s.toolVersion(),
                    s.arguments(),
                    s.requestedAt(),
                    s.status(),
                    s.startedAt(),
                    s.completedAt(),
                    new ToolResult(
                            original.successful(),
                            "History source unavailable",
                            Map.of("status", "UNAVAILABLE"),
                            List.of(),
                            List.of(),
                            false),
                    s.error(),
                    s.version()));
        }
    }

    /** Existing redaction and summary invalidation remain authoritative, including after Store reopen. */
    public void watchRedactions() {
        ports.messageRedactions().register(original -> {
            long after = 0;
            while (true) {
                var page = ports.state().messagesAfter(original.sessionId(), new MessageCursor(after), 128);
                if (page.isEmpty()) return;
                for (AgentMessage candidate : page) {
                    after = candidate.sequence();
                    if (candidate.status() == MessageStatus.REDACTED
                            || candidate.runId().isEmpty()) continue;
                    var ids = candidate.contents().stream()
                            .filter(ToolResultPart.class::isInstance)
                            .map(ToolResultPart.class::cast)
                            .map(ToolResultPart::toolCallId)
                            .collect(java.util.stream.Collectors.toSet());
                    if (ids.isEmpty()) continue;
                    boolean depends = ports
                            .state()
                            .toolCallsByIds(Map.of(candidate.runId().get(), ids))
                            .stream()
                            .anyMatch(call -> owned(call)
                                    && call.result()
                                            .map(result -> references(
                                                    result, original.id().value()))
                                            .orElse(false));
                    if (depends) ports.state().redactMessage(candidate.id());
                }
            }
        });
    }

    private static boolean references(ToolResult result, String id) {
        return result.structuredData().get(SOURCES) instanceof List<?> refs
                && refs.stream().anyMatch(value -> value instanceof Map<?, ?> ref && id.equals(ref.get("messageId")));
    }

    private static ToolResult result(Map<String, Object> data) {
        return new ToolResult(true, "Persisted history data (untrusted)", data, List.of(), List.of(), false);
    }

    private static ToolResult unavailable() {
        return new ToolResult(
                false, "History source unavailable", Map.of("status", "UNAVAILABLE"), List.of(), List.of(), false);
    }

    private static String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return "";
        if (!(value instanceof String text) || text.length() > 1024) throw new Unavailable();
        return text;
    }

    private static String required(Map<String, Object> args, String key) {
        String value = text(args, key);
        if (value.isBlank()) throw new Unavailable();
        return value;
    }

    private static int number(Map<String, Object> args, String key, int fallback, int min, int max) {
        Object v = args.get(key);
        if (v == null) return fallback;
        if (!(v instanceof Number n) || n.doubleValue() != n.longValue() || n.longValue() < min || n.longValue() > max)
            throw new Unavailable();
        return n.intValue();
    }

    private static String digest(Map<String, Object> value) {
        return ToolArgumentsDigest.sha256(new ToolArguments("history-identity", "1", value));
    }

    private static Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private static ToolDefinition definition(
            String name, String description, Map<String, Object> properties, List<String> required) {
        return new ToolDefinition(
                new ToolName(name),
                new SemanticVersion("1.0.0"),
                ID,
                name,
                description,
                new ToolSchema(
                        "haifa.history." + name + ".input",
                        "1",
                        Map.of(
                                "$schema",
                                ToolSchema.DRAFT_2020_12,
                                "type",
                                "object",
                                "properties",
                                properties,
                                "required",
                                required,
                                "additionalProperties",
                                false)),
                new ToolSchema(
                        "haifa.history.output", "1", Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object")),
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(10),
                "per-run-history",
                ToolIdempotency.IDEMPOTENT,
                ToolRisk.LOW,
                Set.of(),
                ToolResourceRequirements.none(),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "haifa-runtime-core",
                false,
                Set.of("history", "read-only"));
    }

    private static final class Unavailable extends RuntimeException {}
}
