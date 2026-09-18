package io.haifa.agent.store.sqlite.codec;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class StableEnumCodec<E extends Enum<E>> {
    private final Map<String, E> values;

    public StableEnumCodec(Class<E> enumType) {
        Objects.requireNonNull(enumType, "enumType must not be null");
        values = java.util.Arrays.stream(enumType.getEnumConstants())
                .collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));
    }

    public String encode(E value) {
        return Objects.requireNonNull(value, "value must not be null").name();
    }

    public E decode(String value) {
        E decoded = values.get(Objects.requireNonNull(value, "value must not be null"));
        if (decoded == null) {
            throw new PayloadCodecException(PayloadCodecFailure.DECODE_FAILED, "Unknown stable enum value");
        }
        return decoded;
    }
}
