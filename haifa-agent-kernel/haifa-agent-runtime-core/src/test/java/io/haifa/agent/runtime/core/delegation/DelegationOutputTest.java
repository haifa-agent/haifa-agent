package io.haifa.agent.runtime.core.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DelegationOutputTest {
    @Test
    void identifiesExactUtf8OutputIncludingEmptyText() {
        var empty = DelegationOutput.metadata("");
        assertThat(empty.get("outputPreview")).isEqualTo("");
        assertThat(empty.get("outputTruncated")).isEqualTo(false);
        assertThat(empty.get("outputSha256"))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(DelegationOutput.metadata("abc").get("outputSha256"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void exactBoundaryDoesNotTruncateSupplementaryCharacters() {
        String output = "😀".repeat(2_000);
        var data = DelegationOutput.metadata(output);
        assertThat(data.get("outputPreview")).isEqualTo(output);
        assertThat(data.get("outputTruncated")).isEqualTo(false);
    }

    @Test
    void previewRetainsHeadAndTailButDigestStillIdentifiesTheCompleteOutput() {
        String output = "😀".repeat(1_500) + "界".repeat(1_500) + "尾";
        var data = DelegationOutput.metadata(output);
        String preview = (String) data.get("outputPreview");
        assertThat(preview.codePointCount(0, preview.length())).isEqualTo(2_000);
        assertThat(preview.startsWith("😀")).isTrue();
        assertThat(preview.contains("\n...\n")).isTrue();
        assertThat(preview.endsWith("尾")).isTrue();
        assertThat(preview.contains("\uFFFD")).isFalse();
        assertThat(data.get("outputTruncated")).isEqualTo(true);
        assertThat(data.get("outputSha256"))
                .isEqualTo("3b4faac3b87978feeb98abccf76d9578966cb53d7c95173e9a17c91918a78b60");
        assertThat(DelegationOutput.metadata(preview).get("outputSha256")).isNotEqualTo(data.get("outputSha256"));
        assertThat(DelegationOutput.metadata(output)).isEqualTo(data);
    }

    @Test
    void metadataIsImmutableAndNullIsNotAnEmptyOutput() {
        var data = DelegationOutput.metadata("answer");
        assertThatThrownBy(() -> data.put("status", "COMPLETED")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> DelegationOutput.metadata(null)).isInstanceOf(NullPointerException.class);
    }
}
