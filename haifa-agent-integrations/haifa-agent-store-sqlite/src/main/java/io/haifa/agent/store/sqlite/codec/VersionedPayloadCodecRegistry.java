package io.haifa.agent.store.sqlite.codec;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.haifa.agent.common.time.TimePrecision;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Closed registry for explicitly declared DTO payloads. It never enables default typing or accepts an
 * arbitrary target class at decode time.
 */
public final class VersionedPayloadCodecRegistry {
    private final ObjectMapper mapper;
    private final int maximumPayloadBytes;
    private final Map<Key, PayloadType<?>> types;

    private VersionedPayloadCodecRegistry(int maximumPayloadBytes, Map<Key, PayloadType<?>> types) {
        if (maximumPayloadBytes < 1) {
            throw new IllegalArgumentException("maximumPayloadBytes must be positive");
        }
        this.maximumPayloadBytes = maximumPayloadBytes;
        this.types = Map.copyOf(types);
        this.mapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(millisecondJavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    private static JavaTimeModule millisecondJavaTimeModule() {
        JavaTimeModule module = new JavaTimeModule();
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator generator, SerializerProvider serializers)
                    throws IOException {
                generator.writeString(TimePrecision.toMilliseconds(value).toString());
            }
        });
        return module;
    }

    public static Builder builder(int maximumPayloadBytes) {
        return new Builder(maximumPayloadBytes);
    }

    public <T> EncodedPayload encode(PayloadType<T> type, T value) {
        PayloadType<T> registered = requireRegistered(type);
        if (!registered.dtoType().isInstance(value)) {
            throw failure(PayloadCodecFailure.TYPE_MISMATCH, "Payload value does not match its registered DTO type");
        }
        try {
            byte[] bytes = mapper.writeValueAsBytes(value);
            checkSize(bytes);
            return new EncodedPayload(type.name(), type.schemaVersion(), bytes, PayloadHashes.sha256(bytes));
        } catch (JsonProcessingException exception) {
            throw new PayloadCodecException(
                    PayloadCodecFailure.ENCODE_FAILED, "Unable to encode payload DTO", exception);
        }
    }

    public <T> T decode(PayloadType<T> expectedType, EncodedPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        PayloadType<T> registered = requireRegistered(expectedType);
        if (!payload.type().equals(registered.name())) {
            throw failure(PayloadCodecFailure.UNKNOWN_TYPE, "Payload type does not match the registered DTO");
        }
        if (!payload.schemaVersion().equals(registered.schemaVersion())) {
            throw failure(PayloadCodecFailure.UNKNOWN_VERSION, "Payload schema version is not registered");
        }
        byte[] bytes = payload.bytes();
        checkSize(bytes);
        if (!PayloadHashes.matches(bytes, payload.hash())) {
            throw failure(PayloadCodecFailure.HASH_MISMATCH, "Payload hash does not match its bytes");
        }
        try {
            return mapper.readValue(bytes, registered.dtoType());
        } catch (UnrecognizedPropertyException exception) {
            throw new PayloadCodecException(
                    PayloadCodecFailure.UNKNOWN_FIELD, "Payload contains an unknown field", exception);
        } catch (IOException exception) {
            throw new PayloadCodecException(
                    PayloadCodecFailure.DECODE_FAILED, "Unable to decode payload DTO", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> PayloadType<T> requireRegistered(PayloadType<T> requested) {
        Objects.requireNonNull(requested, "requested payload type must not be null");
        PayloadType<?> registered = types.get(new Key(requested.name(), requested.schemaVersion()));
        if (registered == null) {
            boolean knownName =
                    types.keySet().stream().anyMatch(key -> key.name().equals(requested.name()));
            throw failure(
                    knownName ? PayloadCodecFailure.UNKNOWN_VERSION : PayloadCodecFailure.UNKNOWN_TYPE,
                    knownName ? "Payload schema version is not registered" : "Payload type is not registered");
        }
        if (!registered.dtoType().equals(requested.dtoType())) {
            throw failure(PayloadCodecFailure.TYPE_MISMATCH, "Registered payload DTO type does not match");
        }
        return (PayloadType<T>) registered;
    }

    private void checkSize(byte[] bytes) {
        if (bytes.length > maximumPayloadBytes) {
            throw failure(PayloadCodecFailure.PAYLOAD_TOO_LARGE, "Payload exceeds the configured byte limit");
        }
    }

    private static PayloadCodecException failure(PayloadCodecFailure failure, String message) {
        return new PayloadCodecException(failure, message);
    }

    private record Key(String name, String version) {}

    public static final class Builder {
        private final int maximumPayloadBytes;
        private final Map<Key, PayloadType<?>> types = new HashMap<>();

        private Builder(int maximumPayloadBytes) {
            this.maximumPayloadBytes = maximumPayloadBytes;
        }

        public <T> Builder register(PayloadType<T> type) {
            Objects.requireNonNull(type, "type must not be null");
            Key key = new Key(type.name(), type.schemaVersion());
            if (types.putIfAbsent(key, type) != null) {
                throw new IllegalArgumentException("payload type and version are already registered");
            }
            return this;
        }

        public VersionedPayloadCodecRegistry build() {
            return new VersionedPayloadCodecRegistry(maximumPayloadBytes, types);
        }
    }
}
