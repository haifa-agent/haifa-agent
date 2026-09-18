package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.credential.core.DefaultSecretRedactor;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliCodingSessionExportServiceTest {
    @TempDir
    Path root;

    @Test
    void destinationMustBeNewAndStayInsideAnExistingWorkspaceDirectory() throws Exception {
        Path exports = Files.createDirectory(root.resolve("exports"));

        assertThat(CliCodingSessionExportService.resolveDestination(root, "exports/session.jsonl"))
                .isEqualTo(exports.resolve("session.jsonl"));
        assertThatThrownBy(() -> CliCodingSessionExportService.resolveDestination(root, "../outside.jsonl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("EXPORT_PATH_OUTSIDE_WORKSPACE");

        Files.writeString(exports.resolve("existing.jsonl"), "existing");
        assertThatThrownBy(() -> CliCodingSessionExportService.resolveDestination(root, "exports/existing.jsonl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("EXPORT_DESTINATION_UNAVAILABLE");
    }

    @Test
    void previewRedactsCredentialPatternsControlsAndOversizedText() {
        String value = "Authorization: Bearer secret-value\u0007\n" + "x".repeat(700);

        String safe = CliCodingSessionExportService.bounded(value, new DefaultSecretRedactor());

        assertThat(safe)
                .contains("Authorization: Bearer [REDACTED]")
                .doesNotContain("secret-value", "\u0007")
                .endsWith("…")
                .hasSize(513);
    }
}
