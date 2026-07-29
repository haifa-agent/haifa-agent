package io.haifa.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.context.item.BoundedTextAssetProcessor;
import io.haifa.agent.context.item.DerivedTextKind;
import io.haifa.agent.context.item.TextAssetDerivationPolicy;
import io.haifa.agent.core.reference.AssetRef;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoundedTextAssetProcessorTest {
    private static final String SECRET = "private-body-must-not-leak";
    private final BoundedTextAssetProcessor processor = new BoundedTextAssetProcessor(
            new TextAssetDerivationPolicy(Set.of("text/plain", "text/markdown", "application/json"), 64, 32));

    @Test
    void derivesAllowedUtf8MediaWithoutRetainingBom() {
        var plain = derive("text/plain", "plain", DerivedTextKind.EXTRACTED_TEXT);
        assertThat(plain.asset()).isEqualTo(asset("text/plain"));
        assertThat(plain.kind()).isEqualTo(DerivedTextKind.EXTRACTED_TEXT);
        assertThat(plain.text()).isEqualTo("plain");
        assertThat(derive("text/markdown; charset=\"UTF-8\"", "\ufeff# title", DerivedTextKind.OCR)
                        .text())
                .isEqualTo("# title");
        assertThat(derive("application/json;charset=utf-8", "{\"ok\":true}", DerivedTextKind.TRANSCRIPT)
                        .text())
                .isEqualTo("{\"ok\":true}");
    }

    @Test
    void rejectsUnsupportedOrUnsafeInputWithStableSafeErrors() {
        rejects("image/png", SECRET.getBytes(StandardCharsets.UTF_8), "ASSET_TEXT_MEDIA_TYPE_NOT_ALLOWED");
        rejects(
                "text/plain; charset=iso-8859-1",
                SECRET.getBytes(StandardCharsets.UTF_8),
                "ASSET_TEXT_CHARSET_NOT_SUPPORTED");
        rejects("text/plain; level=1", SECRET.getBytes(StandardCharsets.UTF_8), "ASSET_TEXT_INVALID_MEDIA_TYPE");
        rejects("text/plain", new byte[] {(byte) 0xc3, (byte) 0x28}, "ASSET_TEXT_INVALID_UTF8");
        rejects("text/plain", "a\u0000b".getBytes(StandardCharsets.UTF_8), "ASSET_TEXT_NUL_NOT_ALLOWED");
        rejects("text/plain", " ".getBytes(StandardCharsets.UTF_8), "ASSET_TEXT_EMPTY");
        rejects("text/plain", "x".repeat(65).getBytes(StandardCharsets.UTF_8), "ASSET_TEXT_INPUT_TOO_LARGE");
        rejects("text/plain", "界".repeat(33).getBytes(StandardCharsets.UTF_8), "ASSET_TEXT_INPUT_TOO_LARGE");

        var characterLimited =
                new BoundedTextAssetProcessor(new TextAssetDerivationPolicy(Set.of("text/plain"), 128, 4));
        assertThatThrownBy(() -> characterLimited.derive(
                        asset("text/plain"), "12345".getBytes(StandardCharsets.UTF_8), DerivedTextKind.EXTRACTED_TEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ASSET_TEXT_OUTPUT_TOO_LARGE");
    }

    @Test
    void policyRejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new TextAssetDerivationPolicy(Set.of(), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextAssetDerivationPolicy(Set.of("image/png"), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextAssetDerivationPolicy(Set.of("text/plain; charset=utf-8"), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextAssetDerivationPolicy(Set.of("text/plain"), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(processor.toString()).doesNotContain(SECRET);
    }

    private io.haifa.agent.context.item.AssetDerivedTextContent derive(
            String mediaType, String content, DerivedTextKind kind) {
        return processor.derive(asset(mediaType), content.getBytes(StandardCharsets.UTF_8), kind);
    }

    private void rejects(String mediaType, byte[] content, String code) {
        assertThatThrownBy(() -> processor.derive(asset(mediaType), content, DerivedTextKind.EXTRACTED_TEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(code)
                .hasMessageNotContaining(SECRET);
    }

    private static AssetRef asset(String mediaType) {
        return new AssetRef("asset-1", mediaType, "source.txt");
    }
}
