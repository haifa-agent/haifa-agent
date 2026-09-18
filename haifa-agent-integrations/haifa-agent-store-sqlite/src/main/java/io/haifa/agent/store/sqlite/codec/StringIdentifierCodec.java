package io.haifa.agent.store.sqlite.codec;

import java.util.Objects;
import java.util.function.Function;

public final class StringIdentifierCodec<T> {
    private final Function<T, String> encoder;
    private final Function<String, T> decoder;

    public StringIdentifierCodec(Function<T, String> encoder, Function<String, T> decoder) {
        this.encoder = Objects.requireNonNull(encoder, "encoder must not be null");
        this.decoder = Objects.requireNonNull(decoder, "decoder must not be null");
    }

    public String encode(T value) {
        String encoded = encoder.apply(Objects.requireNonNull(value, "value must not be null"));
        if (encoded == null || encoded.isBlank()) {
            throw new PayloadCodecException(PayloadCodecFailure.ENCODE_FAILED, "Identifier encoded to a blank value");
        }
        return encoded;
    }

    public T decode(String value) {
        if (value == null || value.isBlank()) {
            throw new PayloadCodecException(PayloadCodecFailure.DECODE_FAILED, "Identifier value is blank");
        }
        try {
            return Objects.requireNonNull(decoder.apply(value), "decoded identifier must not be null");
        } catch (RuntimeException exception) {
            throw new PayloadCodecException(
                    PayloadCodecFailure.DECODE_FAILED, "Identifier value is invalid", exception);
        }
    }
}
