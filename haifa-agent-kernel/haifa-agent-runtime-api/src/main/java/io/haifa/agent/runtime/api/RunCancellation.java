package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.RunTerminationReason;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Trusted reason attached to a Runtime cancellation request. */
public record RunCancellation(Type type, OptionalLong limitMillis, Optional<Instant> deadlineAt) {
    static final String SCHEMA_ID = "runtime.cancel.v1";
    static final String SCHEMA_VERSION = "1.0";

    public RunCancellation {
        type = Objects.requireNonNull(type, "type must not be null");
        limitMillis = Objects.requireNonNull(limitMillis, "limitMillis must not be null");
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        if (limitMillis.isPresent() && limitMillis.getAsLong() < 1) {
            throw new IllegalArgumentException("limitMillis must be positive");
        }
        if (type == Type.USER_REQUEST && (limitMillis.isPresent() || deadlineAt.isPresent())) {
            throw new IllegalArgumentException("USER_REQUEST must not carry a deadline");
        }
        if (type == Type.DEADLINE_EXCEEDED && limitMillis.isEmpty() && deadlineAt.isEmpty()) {
            throw new IllegalArgumentException("DEADLINE_EXCEEDED requires a configured deadline");
        }
        if (limitMillis.isPresent() && deadlineAt.isPresent()) {
            throw new IllegalArgumentException("deadline must be expressed as a duration or an instant, not both");
        }
    }

    public static RunCancellation userRequest() {
        return new RunCancellation(Type.USER_REQUEST, OptionalLong.empty(), Optional.empty());
    }

    public static RunCancellation deadlineExceeded(Duration limit) {
        Objects.requireNonNull(limit, "limit must not be null");
        long millis;
        try {
            millis = limit.toMillis();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("limit is too large", overflow);
        }
        return new RunCancellation(Type.DEADLINE_EXCEEDED, OptionalLong.of(millis), Optional.empty());
    }

    public static RunCancellation deadlineExceededAt(Instant deadlineAt) {
        return new RunCancellation(
                Type.DEADLINE_EXCEEDED, OptionalLong.empty(), Optional.of(Objects.requireNonNull(deadlineAt)));
    }

    public RuntimeCommandArguments arguments() {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("reason", type.name());
        limitMillis.ifPresent(value -> values.put("limitMillis", value));
        deadlineAt.ifPresent(value -> values.put("deadlineAtEpochMillis", value.toEpochMilli()));
        return new RuntimeCommandArguments(SCHEMA_ID, SCHEMA_VERSION, values);
    }

    public RunTerminationReason terminationReason() {
        if (type == Type.USER_REQUEST) {
            return new RunTerminationReason("USER_CANCELLED", "Cancellation requested by the user");
        }
        String description = limitMillis.isPresent()
                ? "Run deadline of " + limitMillis.getAsLong() + " ms exceeded"
                : "Run deadline at " + deadlineAt.orElseThrow() + " exceeded";
        return new RunTerminationReason("DEADLINE_EXCEEDED", description);
    }

    public static RunCancellation from(RuntimeCommandArguments arguments) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        if (arguments.equals(RuntimeCommandArguments.NONE)) return userRequest();
        if (!SCHEMA_ID.equals(arguments.schemaId()) || !SCHEMA_VERSION.equals(arguments.schemaVersion())) {
            throw new IllegalArgumentException("CANCEL requires runtime.cancel.v1 arguments");
        }
        Map<String, Object> values = arguments.values();
        if (!values.keySet().stream()
                .allMatch(key ->
                        key.equals("reason") || key.equals("limitMillis") || key.equals("deadlineAtEpochMillis"))) {
            throw new IllegalArgumentException("CANCEL arguments contain unsupported fields");
        }
        Type type;
        try {
            type = Type.valueOf(Objects.toString(values.get("reason"), ""));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("CANCEL reason is invalid", failure);
        }
        OptionalLong limit = number(values.get("limitMillis"));
        Optional<Instant> deadline = number(values.get("deadlineAtEpochMillis")).isPresent()
                ? Optional.of(Instant.ofEpochMilli(
                        number(values.get("deadlineAtEpochMillis")).getAsLong()))
                : Optional.empty();
        return new RunCancellation(type, limit, deadline);
    }

    private static OptionalLong number(Object value) {
        if (value == null) return OptionalLong.empty();
        if (!(value instanceof Number number)) throw new IllegalArgumentException("CANCEL deadline must be numeric");
        return OptionalLong.of(number.longValue());
    }

    public enum Type {
        USER_REQUEST,
        DEADLINE_EXCEEDED
    }
}
