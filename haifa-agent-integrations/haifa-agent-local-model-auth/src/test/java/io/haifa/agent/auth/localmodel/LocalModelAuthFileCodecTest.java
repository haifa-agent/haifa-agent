package io.haifa.agent.auth.localmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalModelAuthFileCodecTest {
    private final LocalModelAuthFileCodec codec = new LocalModelAuthFileCodec(new ObjectMapper());

    @Test
    void roundTripsTheVersionedDiscriminatedUnion() {
        LocalModelAuthReference api = LocalModelAuthReference.parse("model-auth://deepseek/default");
        LocalModelAuthReference external = LocalModelAuthReference.parse("model-auth://openai-codex/default");
        Map<LocalModelAuthReference, StoredModelCredential> values = new LinkedHashMap<>();
        values.put(api, new StoredApiKeyCredential(api, "api-secret"));
        values.put(
                external,
                new StoredExternalCredential(
                        external,
                        ExternalLoginMethodId.OPENAI_CODEX,
                        "registration",
                        "access-secret",
                        "refresh-secret",
                        2_000,
                        1_000,
                        "account"));

        Map<LocalModelAuthReference, StoredModelCredential> decoded = codec.decode(codec.encode(values));

        assertThat(decoded).containsOnlyKeys(api, external);
        assertThat(decoded.get(api)).isInstanceOf(StoredApiKeyCredential.class);
        assertThat(decoded.get(external)).isInstanceOf(StoredExternalCredential.class);
    }

    @Test
    void rejectsUnknownFieldsKindsSchemesAndOversizedFiles() {
        assertThatThrownBy(() -> codec.decode("{\"version\":1,\"credentials\":{},\"extra\":true}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected");
        assertThatThrownBy(() ->
                        codec.decode("{\"version\":1,\"credentials\":{\"model-auth://x/y\":{\"kind\":\"UNKNOWN\"}}}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported");
        assertThatThrownBy(() -> codec.decode(new byte[LocalModelAuthFileCodec.MAX_FILE_BYTES + 1]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("size");
    }
}
