package io.haifa.agent.personalassistant.server.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalAudioStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesOpaqueReferenceAndResolvesVerifiedWavBytes() {
        var store = new PersonalAudioStore(temporaryDirectory);
        byte[] wav = wav();

        var saved = store.save(wav, "audio/wav", "../private/verification.wav");
        var reloaded = store.reference(saved.audioId());

        assertThat(saved.storeId()).isEqualTo(PersonalAudioStore.STORE_ID);
        assertThat(saved.originalFilename()).isEqualTo("verification.wav");
        assertThat(reloaded.sha256()).isEqualTo(saved.sha256());
        assertThat(store.resolve(saved).bytes()).containsExactly(wav);
        assertThat(saved.toString())
                .doesNotContain(temporaryDirectory.toString())
                .doesNotContain(saved.sha256());
    }

    @Test
    void normalizesBrowserMp3MimeAndRejectsSpoofedAudio() {
        var store = new PersonalAudioStore(temporaryDirectory);
        byte[] mp3 = new byte[] {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};

        assertThat(store.save(mp3, "audio/mpeg", "sample.mp3").mediaType()).isEqualTo("audio/mp3");
        assertThatThrownBy(() -> store.save(wav(), "audio/mp3", "spoofed.mp3"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.reference("../../secret")).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] wav() {
        return "RIFF\u0004\u0000\u0000\u0000WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1);
    }
}
