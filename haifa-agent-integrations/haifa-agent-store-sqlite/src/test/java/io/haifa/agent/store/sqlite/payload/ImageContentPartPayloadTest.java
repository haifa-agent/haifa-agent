package io.haifa.agent.store.sqlite.payload;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.content.ImageUrlContentPart;
import io.haifa.agent.core.content.StoredImageContentPart;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ImageContentPartPayloadTest {
    @Test
    void roundTripsRemoteAndStoredImagesWithoutBinaryOrPaths() {
        var remote = new ImageUrlContentPart(URI.create("https://images.example.test/cat.png"));
        var stored = new StoredImageContentPart(
                "personal-local", "image-1", "image/png", 9, "sha256:" + "a".repeat(64), "cat.png");

        var remotePayload = ContentPartPayload.from(remote);
        var storedPayload = ContentPartPayload.from(stored);

        assertThat(remotePayload.toDomain()).isEqualTo(remote);
        assertThat(storedPayload.toDomain()).isEqualTo(stored);
        assertThat(storedPayload.toString())
                .doesNotContain("data:")
                .doesNotContain("\\")
                .doesNotContain("/tmp/");
    }
}
