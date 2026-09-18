package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.model.api.ImageDataPart;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryImageStoreTest {

    @Test
    void storesAndResolvesImageWithDeterministicIdentifierGenerator() {
        AtomicInteger counter = new AtomicInteger(100);
        IdentifierGenerator deterministicIds = () -> "image-id-" + counter.getAndIncrement();
        InMemoryImageStore store = new InMemoryImageStore("custom-store", deterministicIds);

        byte[] rawBytes = "fake-png-content".getBytes(StandardCharsets.UTF_8);
        StoredImageContentPart stored = store.store(rawBytes, "image/png", "sample.png");

        assertThat(stored.storeId()).isEqualTo("custom-store");
        assertThat(stored.imageId()).isEqualTo("image-id-100");
        assertThat(stored.mediaType()).isEqualTo("image/png");
        assertThat(stored.originalFilename()).isEqualTo("sample.png");
        assertThat(stored.sizeBytes()).isEqualTo(rawBytes.length);
        assertThat(stored.sha256()).startsWith("sha256:");

        ImageDataPart resolved = store.resolve(stored);
        assertThat(resolved.mediaType()).isEqualTo("image/png");
        assertThat(resolved.bytes()).isEqualTo(rawBytes);

        // Cloned bytes verify immutability
        rawBytes[0] = 'X';
        assertThat(store.resolve(stored).bytes()[0]).isNotEqualTo((byte) 'X');
    }

    @Test
    void defaultConstructorsInitializeProperly() {
        InMemoryImageStore defaultStore = new InMemoryImageStore();
        byte[] bytes = new byte[] {1, 2, 3};
        StoredImageContentPart stored = defaultStore.store(bytes, "image/webp", "test.webp");

        assertThat(stored.storeId()).isEqualTo(InMemoryImageStore.DEFAULT_STORE_ID);
        assertThat(stored.imageId()).isNotBlank();
        assertThat(defaultStore.resolve(stored)).isNotNull();

        InMemoryImageStore storeWithId = new InMemoryImageStore("my-store");
        StoredImageContentPart stored2 = storeWithId.store(bytes, "image/jpeg", "test.jpeg");
        assertThat(stored2.storeId()).isEqualTo("my-store");

        InMemoryImageStore storeWithGenerator = new InMemoryImageStore(() -> "fixed-id");
        StoredImageContentPart stored3 = storeWithGenerator.store(bytes, "image/png", "fixed.png");
        assertThat(stored3.imageId()).isEqualTo("fixed-id");
        assertThat(stored3.storeId()).isEqualTo(InMemoryImageStore.DEFAULT_STORE_ID);
    }

    @Test
    void rejectsInvalidArgumentsAndSizes() {
        InMemoryImageStore store = new InMemoryImageStore();

        assertThatThrownBy(() -> new InMemoryImageStore((String) null, () -> "id"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new InMemoryImageStore("store", null)).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> store.store(new byte[0], "image/png", "empty.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image must contain 1 byte to 10 MiB");

        assertThatThrownBy(() ->
                        store.store(new byte[InMemoryImageStore.MAXIMUM_IMAGE_BYTES + 1], "image/png", "huge.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image must contain 1 byte to 10 MiB");
    }

    @Test
    void throwsForDifferentStoreOrUnknownId() {
        InMemoryImageStore store = new InMemoryImageStore("store-a");
        StoredImageContentPart diffStorePart = new StoredImageContentPart(
                "store-b",
                "img-1",
                "image/png",
                10,
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "other.png");
        assertThatThrownBy(() -> store.resolve(diffStorePart))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported storeId: store-b");

        StoredImageContentPart unknownIdPart = new StoredImageContentPart(
                "store-a",
                "non-existent",
                "image/png",
                10,
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "other.png");
        assertThatThrownBy(() -> store.resolve(unknownIdPart))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("image is unavailable: non-existent");
    }
}
