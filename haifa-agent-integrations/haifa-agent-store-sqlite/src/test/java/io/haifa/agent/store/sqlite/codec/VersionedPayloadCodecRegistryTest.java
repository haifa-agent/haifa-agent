package io.haifa.agent.store.sqlite.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VersionedPayloadCodecRegistryTest {
    private static final PayloadType<SampleDto> SAMPLE = new PayloadType<>("sample", "1", SampleDto.class);

    @Test
    void roundTripsRegisteredDtoWithStableBytesAndHash() {
        VersionedPayloadCodecRegistry codecs =
                VersionedPayloadCodecRegistry.builder(1_024).register(SAMPLE).build();

        EncodedPayload first = codecs.encode(SAMPLE, new SampleDto("value", 7));
        EncodedPayload second = codecs.encode(SAMPLE, new SampleDto("value", 7));

        assertThat(first.bytes()).isEqualTo(second.bytes());
        assertThat(first.hash()).isEqualTo(second.hash()).startsWith("sha256:");
        assertThat(codecs.decode(SAMPLE, first)).isEqualTo(new SampleDto("value", 7));
    }

    @Test
    void rejectsUnknownTypeVersionFieldOversizeAndHashMismatch() {
        VersionedPayloadCodecRegistry codecs =
                VersionedPayloadCodecRegistry.builder(128).register(SAMPLE).build();
        EncodedPayload encoded = codecs.encode(SAMPLE, new SampleDto("ok", 1));

        assertFailure(
                () -> codecs.encode(new PayloadType<>("unknown", "1", SampleDto.class), new SampleDto("x", 1)),
                PayloadCodecFailure.UNKNOWN_TYPE);
        assertFailure(
                () -> codecs.decode(
                        new PayloadType<>("sample", "2", SampleDto.class),
                        new EncodedPayload("sample", "2", encoded.bytes(), encoded.hash())),
                PayloadCodecFailure.UNKNOWN_VERSION);
        assertFailure(
                () -> codecs.decode(
                        SAMPLE,
                        payload(
                                "sample",
                                "1",
                                "{\"count\":1,\"name\":\"ok\",\"unknown\":true}".getBytes(StandardCharsets.UTF_8))),
                PayloadCodecFailure.UNKNOWN_FIELD);
        assertFailure(
                () -> codecs.decode(
                        SAMPLE, new EncodedPayload("sample", "1", encoded.bytes(), "sha256:" + "0".repeat(64))),
                PayloadCodecFailure.HASH_MISMATCH);
        assertFailure(
                () -> codecs.encode(SAMPLE, new SampleDto("x".repeat(256), 1)), PayloadCodecFailure.PAYLOAD_TOO_LARGE);
    }

    private static EncodedPayload payload(String type, String version, byte[] bytes) {
        return new EncodedPayload(type, version, bytes, PayloadHashes.sha256(bytes));
    }

    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, Object failure) {
        assertThatThrownBy(callable)
                .isInstanceOf(PayloadCodecException.class)
                .extracting(exception -> ((PayloadCodecException) exception).failure())
                .isEqualTo(failure);
    }

    record SampleDto(String name, int count) {}
}
