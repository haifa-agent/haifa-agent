package io.haifa.agent.store.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.orchestration.api.WorkflowCheckpoint;
import io.haifa.agent.orchestration.api.WorkflowCheckpointId;
import io.haifa.agent.orchestration.api.WorkflowDefinitionDigest;
import io.haifa.agent.orchestration.api.WorkflowDefinitionId;
import io.haifa.agent.orchestration.api.WorkflowDefinitionRef;
import io.haifa.agent.orchestration.api.WorkflowDefinitionVersion;
import io.haifa.agent.orchestration.api.WorkflowErrorCode;
import io.haifa.agent.orchestration.api.WorkflowFailure;
import io.haifa.agent.orchestration.api.WorkflowNodeAttempt;
import io.haifa.agent.orchestration.api.WorkflowNodeAttemptStatus;
import io.haifa.agent.orchestration.api.WorkflowNodeId;
import io.haifa.agent.orchestration.api.WorkflowRunId;
import io.haifa.agent.orchestration.api.WorkflowRunSnapshot;
import io.haifa.agent.orchestration.api.WorkflowState;
import io.haifa.agent.orchestration.api.WorkflowStateDelta;
import io.haifa.agent.orchestration.api.WorkflowStateSchema;
import io.haifa.agent.orchestration.api.WorkflowStatus;
import io.haifa.agent.orchestration.api.WorkflowWait;
import io.haifa.agent.orchestration.api.WorkflowWaitId;
import io.haifa.agent.orchestration.core.spi.WorkflowForkState;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Strict version-one codec for provider-neutral Workflow persistence payloads. */
final class SqliteWorkflowCodec {
    static final int VERSION = 1;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maximumBytes;

    SqliteWorkflowCodec(int maximumBytes) {
        this.maximumBytes = maximumBytes;
    }

    Encoded encodeState(WorkflowState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaId", state.schema().schemaId());
        payload.put("schemaVersion", state.schema().version());
        payload.put(
                "allowedKeys", state.schema().allowedKeys().stream().sorted().toList());
        payload.put("maximumValues", state.schema().maximumValues());
        payload.put("maximumDepth", state.schema().maximumDepth());
        payload.put("maximumStringLength", state.schema().maximumStringLength());
        payload.put("values", encodeValue(state.values()));
        return encode(payload);
    }

    WorkflowState decodeState(byte[] payload, String hash) {
        Map<String, Object> value = decode(payload, hash);
        WorkflowStateSchema schema = new WorkflowStateSchema(
                string(value, "schemaId"),
                number(value, "schemaVersion").longValue(),
                Set.copyOf(strings(value.get("allowedKeys"))),
                number(value, "maximumValues").intValue(),
                number(value, "maximumDepth").intValue(),
                number(value, "maximumStringLength").intValue());
        return new WorkflowState(schema, stringObjectMap(decodeValue(value.get("values"))));
    }

    Encoded encodeDelta(WorkflowStateDelta delta) {
        return encode(Map.of("values", encodeValue(delta.values())));
    }

    WorkflowStateDelta decodeDelta(byte[] payload, String hash) {
        return new WorkflowStateDelta(
                stringObjectMap(decodeValue(decode(payload, hash).get("values"))));
    }

    Encoded encodeControl(
            Map<String, Integer> visits,
            Set<String> consumedSignals,
            Optional<WorkflowForkState> fork,
            Optional<AgentRunId> pendingCancellation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("visits", new TreeMap<>(visits));
        payload.put("consumedSignals", consumedSignals.stream().sorted().toList());
        payload.put("fork", fork.map(this::encodeFork).orElse(null));
        payload.put(
                "pendingAgentCancellation",
                pendingCancellation.map(AgentRunId::value).orElse(null));
        return encode(payload);
    }

    Control decodeControl(byte[] payload, String hash) {
        Map<String, Object> value = decode(payload, hash);
        Map<String, Integer> visits = new LinkedHashMap<>();
        map(value.get("visits")).forEach((key, count) -> visits.put(key, ((Number) count).intValue()));
        Object fork = value.get("fork");
        return new Control(
                visits,
                Set.copyOf(strings(value.get("consumedSignals"))),
                fork == null ? Optional.empty() : Optional.of(decodeFork(map(fork))),
                Optional.ofNullable((String) value.get("pendingAgentCancellation"))
                        .map(AgentRunId::new));
    }

    Encoded encodeSnapshot(WorkflowRunSnapshot snapshot) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("runId", snapshot.id().value());
        value.put("definitionId", snapshot.definition().id().value());
        value.put("definitionVersion", snapshot.definition().version().value());
        value.put("definitionDigest", snapshot.definition().digest().value());
        value.put("status", snapshot.status().name());
        value.put("revision", snapshot.revision());
        value.put("state", decodeRaw(encodeState(snapshot.state()).payload()));
        value.put(
                "currentNode", snapshot.currentNode().map(WorkflowNodeId::value).orElse(null));
        value.put(
                "wait",
                snapshot.activeWait().map(SqliteWorkflowCodec::encodeWait).orElse(null));
        value.put(
                "checkpoint", snapshot.checkpoint().map(this::encodeCheckpoint).orElse(null));
        value.put(
                "failure",
                snapshot.failure().map(SqliteWorkflowCodec::encodeFailure).orElse(null));
        value.put(
                "attempts",
                snapshot.attempts().stream()
                        .map(SqliteWorkflowCodec::encodeAttempt)
                        .toList());
        value.put("createdAt", snapshot.createdAt().toEpochMilli());
        value.put("updatedAt", snapshot.updatedAt().toEpochMilli());
        return encode(value);
    }

    WorkflowRunSnapshot decodeSnapshot(byte[] payload, String hash) {
        Map<String, Object> value = decode(payload, hash);
        WorkflowDefinitionRef reference = new WorkflowDefinitionRef(
                new WorkflowDefinitionId(string(value, "definitionId")),
                new WorkflowDefinitionVersion(number(value, "definitionVersion").longValue()),
                new WorkflowDefinitionDigest(string(value, "definitionDigest")));
        List<WorkflowNodeAttempt> attempts = list(value.get("attempts")).stream()
                .map(item -> decodeAttempt(map(item)))
                .toList();
        return new WorkflowRunSnapshot(
                new WorkflowRunId(string(value, "runId")),
                reference,
                WorkflowStatus.valueOf(string(value, "status")),
                number(value, "revision").longValue(),
                decodeState(
                        encode(map(value.get("state"))).payload(),
                        encode(map(value.get("state"))).hash()),
                Optional.ofNullable((String) value.get("currentNode")).map(WorkflowNodeId::new),
                Optional.ofNullable(value.get("wait")).map(item -> decodeWait(map(item))),
                Optional.ofNullable(value.get("checkpoint")).map(item -> decodeCheckpoint(map(item))),
                Optional.ofNullable(value.get("failure")).map(item -> decodeFailure(map(item))),
                attempts,
                Instant.ofEpochMilli(number(value, "createdAt").longValue()),
                Instant.ofEpochMilli(number(value, "updatedAt").longValue()));
    }

    Encoded encodeStringMap(Map<String, String> values) {
        return encode(new TreeMap<>(values));
    }

    Map<String, String> decodeStringMap(byte[] payload, String hash) {
        Map<String, String> result = new LinkedHashMap<>();
        decode(payload, hash).forEach((key, value) -> result.put(key, (String) value));
        return result;
    }

    private Map<String, Object> encodeFork(WorkflowForkState fork) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("forkNode", fork.forkNode().value());
        value.put("baseState", decodeRaw(encodeState(fork.baseState()).payload()));
        value.put(
                "branchEntries",
                fork.branchEntries().stream().map(WorkflowNodeId::value).toList());
        value.put("branchIndex", fork.branchIndex());
        value.put("cursor", fork.cursor().value());
        value.put(
                "completed",
                fork.completedBranches().stream()
                        .map(branch -> Map.of(
                                "ordinal", branch.ordinal(),
                                "entryNode", branch.entryNode().value(),
                                "delta", decodeRaw(encodeDelta(branch.delta()).payload())))
                        .toList());
        return value;
    }

    private WorkflowForkState decodeFork(Map<String, Object> value) {
        List<WorkflowForkState.CompletedBranch> completed = list(value.get("completed")).stream()
                .map(item -> map(item))
                .map(item -> new WorkflowForkState.CompletedBranch(
                        number(item, "ordinal").intValue(),
                        new WorkflowNodeId(string(item, "entryNode")),
                        decodeDelta(
                                encode(map(item.get("delta"))).payload(),
                                encode(map(item.get("delta"))).hash())))
                .toList();
        Encoded base = encode(map(value.get("baseState")));
        return new WorkflowForkState(
                new WorkflowNodeId(string(value, "forkNode")),
                decodeState(base.payload(), base.hash()),
                strings(value.get("branchEntries")).stream()
                        .map(WorkflowNodeId::new)
                        .toList(),
                number(value, "branchIndex").intValue(),
                new WorkflowNodeId(string(value, "cursor")),
                completed);
    }

    private static Map<String, Object> encodeWait(WorkflowWait wait) {
        return Map.of(
                "id", wait.id().value(),
                "nodeId", wait.nodeId().value(),
                "revision", wait.revision(),
                "createdAt", wait.createdAt().toEpochMilli());
    }

    private static WorkflowWait decodeWait(Map<String, Object> value) {
        return new WorkflowWait(
                new WorkflowWaitId(string(value, "id")),
                new WorkflowNodeId(string(value, "nodeId")),
                number(value, "revision").longValue(),
                Instant.ofEpochMilli(number(value, "createdAt").longValue()));
    }

    private Map<String, Object> encodeCheckpoint(WorkflowCheckpoint checkpoint) {
        return Map.of(
                "id", checkpoint.id().value(),
                "runId", checkpoint.runId().value(),
                "revision", checkpoint.revision(),
                "resumeNode", checkpoint.resumeNode().value(),
                "state", decodeRaw(encodeState(checkpoint.state()).payload()),
                "createdAt", checkpoint.createdAt().toEpochMilli());
    }

    private WorkflowCheckpoint decodeCheckpoint(Map<String, Object> value) {
        Encoded state = encode(map(value.get("state")));
        return new WorkflowCheckpoint(
                new WorkflowCheckpointId(string(value, "id")),
                new WorkflowRunId(string(value, "runId")),
                number(value, "revision").longValue(),
                new WorkflowNodeId(string(value, "resumeNode")),
                decodeState(state.payload(), state.hash()),
                Instant.ofEpochMilli(number(value, "createdAt").longValue()));
    }

    private static Map<String, Object> encodeFailure(WorkflowFailure failure) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", failure.code().name());
        value.put("operation", failure.operation());
        value.put("nodeId", failure.nodeId().map(WorkflowNodeId::value).orElse(null));
        return value;
    }

    private static WorkflowFailure decodeFailure(Map<String, Object> value) {
        return new WorkflowFailure(
                WorkflowErrorCode.valueOf(string(value, "code")),
                string(value, "operation"),
                Optional.ofNullable((String) value.get("nodeId")).map(WorkflowNodeId::new));
    }

    private static Map<String, Object> encodeAttempt(WorkflowNodeAttempt attempt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("nodeId", attempt.nodeId().value());
        value.put("attempt", attempt.attempt());
        value.put("status", attempt.status().name());
        value.put("agentRunId", attempt.agentRunId().map(AgentRunId::value).orElse(null));
        value.put("failureCode", attempt.failureCode().map(Enum::name).orElse(null));
        value.put("startedAt", attempt.startedAt().toEpochMilli());
        value.put("finishedAt", attempt.finishedAt().map(Instant::toEpochMilli).orElse(null));
        return value;
    }

    private static WorkflowNodeAttempt decodeAttempt(Map<String, Object> value) {
        return new WorkflowNodeAttempt(
                new WorkflowNodeId(string(value, "nodeId")),
                number(value, "attempt").intValue(),
                WorkflowNodeAttemptStatus.valueOf(string(value, "status")),
                Optional.ofNullable((String) value.get("agentRunId")).map(AgentRunId::new),
                Optional.ofNullable((String) value.get("failureCode")).map(WorkflowErrorCode::valueOf),
                Instant.ofEpochMilli(number(value, "startedAt").longValue()),
                Optional.ofNullable((Number) value.get("finishedAt"))
                        .map(item -> Instant.ofEpochMilli(item.longValue())));
    }

    private Object encodeValue(Object value) {
        if (value instanceof String string) return Map.of("type", "string", "value", string);
        if (value instanceof Boolean bool) return Map.of("type", "boolean", "value", bool);
        if (value instanceof Integer number) return Map.of("type", "integer", "value", number.toString());
        if (value instanceof Long number) return Map.of("type", "long", "value", number.toString());
        if (value instanceof BigInteger number) return Map.of("type", "big-integer", "value", number.toString());
        if (value instanceof BigDecimal number) return Map.of("type", "big-decimal", "value", number.toPlainString());
        if (value instanceof List<?> list) {
            return Map.of(
                    "type",
                    "list",
                    "value",
                    list.stream().map(this::encodeValue).toList());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new TreeMap<>();
            map.forEach((key, nestedValue) -> nested.put((String) key, encodeValue(nestedValue)));
            return Map.of("type", "map", "value", nested);
        }
        throw new IllegalArgumentException(
                "unsupported workflow value: " + value.getClass().getName());
    }

    private Object decodeValue(Object encoded) {
        Map<String, Object> value = map(encoded);
        return switch (string(value, "type")) {
            case "string" -> value.get("value");
            case "boolean" -> value.get("value");
            case "integer" -> Integer.valueOf((String) value.get("value"));
            case "long" -> Long.valueOf((String) value.get("value"));
            case "big-integer" -> new BigInteger((String) value.get("value"));
            case "big-decimal" -> new BigDecimal((String) value.get("value"));
            case "list" ->
                list(value.get("value")).stream().map(this::decodeValue).toList();
            case "map" -> {
                Map<String, Object> nested = new LinkedHashMap<>();
                map(value.get("value")).forEach((key, item) -> nested.put(key, decodeValue(item)));
                yield nested;
            }
            default -> throw corrupt("unsupported typed workflow state value");
        };
    }

    private Encoded encode(Object value) {
        try {
            byte[] payload = mapper.writeValueAsBytes(value);
            if (payload.length > maximumBytes) throw corrupt("workflow payload exceeds configured maximum");
            return new Encoded(payload, hash(payload));
        } catch (IOException exception) {
            throw corrupt("unable to encode workflow payload", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decode(byte[] payload, String expectedHash) {
        if (payload.length > maximumBytes || !hash(payload).equals(expectedHash)) {
            throw corrupt("workflow payload integrity check failed");
        }
        try {
            return mapper.readValue(payload, Map.class);
        } catch (IOException exception) {
            throw corrupt("unable to decode workflow payload", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeRaw(byte[] payload) {
        try {
            return mapper.readValue(payload, Map.class);
        } catch (IOException exception) {
            throw corrupt("unable to decode internal workflow payload", exception);
        }
    }

    private static String hash(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static List<String> strings(Object value) {
        return list(value).stream().map(String.class::cast).toList();
    }

    private static Number number(Map<String, Object> value, String key) {
        return (Number) value.get(key);
    }

    private static String string(Map<String, Object> value, String key) {
        return (String) value.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringObjectMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static SqliteStoreException corrupt(String message) {
        return new SqliteStoreException(SqliteStoreFailure.WORKFLOW_CORRUPTION, message);
    }

    private static SqliteStoreException corrupt(String message, Throwable cause) {
        return new SqliteStoreException(SqliteStoreFailure.WORKFLOW_CORRUPTION, message, cause);
    }

    record Encoded(byte[] payload, String hash) {
        Encoded {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    record Control(
            Map<String, Integer> visits,
            Set<String> consumedSignals,
            Optional<WorkflowForkState> forkState,
            Optional<AgentRunId> pendingAgentCancellation) {}
}
