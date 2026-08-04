package io.haifa.agent.personalassistant.server.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalImageStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesOpaqueReferenceAndResolvesVerifiedBytes() {
        var store = new PersonalImageStore(temporaryDirectory);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};

        var saved = store.save(png, "image/png", "../private/cat.png");
        var reloaded = store.reference(saved.imageId());

        assertThat(saved.storeId()).isEqualTo(PersonalImageStore.STORE_ID);
        assertThat(saved.originalFilename()).isEqualTo("cat.png");
        assertThat(reloaded.sha256()).isEqualTo(saved.sha256());
        assertThat(store.resolve(saved).bytes()).containsExactly(png);
        assertThat(saved.toString())
                .doesNotContain(temporaryDirectory.toString())
                .doesNotContain(saved.sha256());
    }

    @Test
    void rejectsSpoofedMediaTypesAndInvalidReferences() {
        var store = new PersonalImageStore(temporaryDirectory);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

        assertThatThrownBy(() -> store.save(png, "image/jpeg", "cat.jpg")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.reference("../../secret")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodesAndSanitizesUploadedFilename() {
        var store = new PersonalImageStore(temporaryDirectory);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

        var saved = store.save(png, "image/png", "..%2Fprivate%2F%E7%8C%AB.png");

        assertThat(saved.originalFilename()).isEqualTo("猫.png");
    }

    @Test
    void acceptsSingleFrameGifAndRejectsMultipleFrames() {
        var store = new PersonalImageStore(temporaryDirectory);
        byte[] gif = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==");
        int imageStart = indexOf(gif, (byte) 0x2c);
        byte[] animated = new byte[gif.length + gif.length - imageStart - 1];
        System.arraycopy(gif, 0, animated, 0, gif.length - 1);
        System.arraycopy(gif, imageStart, animated, gif.length - 1, gif.length - imageStart);

        assertThat(store.save(gif, "image/gif", "still.gif").mediaType()).isEqualTo("image/gif");
        assertThatThrownBy(() -> store.save(animated, "image/gif", "animated.gif"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("animated GIF images are not supported");
    }

    private static int indexOf(byte[] bytes, byte value) {
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == value) return index;
        }
        throw new AssertionError("test GIF has no image descriptor");
    }
}
