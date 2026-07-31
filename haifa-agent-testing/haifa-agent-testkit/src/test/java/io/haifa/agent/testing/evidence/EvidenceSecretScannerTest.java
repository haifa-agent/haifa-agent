package io.haifa.agent.testing.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceSecretScannerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void findsSecretAcrossStreamingBufferBoundaryWithoutExposingItsValue() throws Exception {
        String secret = "test-secret-crosses-buffer-boundary";
        Path nested = Files.createDirectories(temporaryDirectory.resolve("nested"));
        Files.writeString(nested.resolve("driver.log"), "x".repeat(8190) + secret);
        Files.writeString(temporaryDirectory.resolve("safe.log"), "safe evidence");

        EvidenceSecretScanner.Result result = EvidenceSecretScanner.scan(temporaryDirectory, List.of(secret));

        assertFalse(result.passed());
        assertEquals(List.of("nested/driver.log"), result.findingPaths());
        assertFalse(result.toString().contains(secret));
    }

    @Test
    void acceptsEvidenceWhenNoNonBlankSecretsWereSelected() throws Exception {
        Files.writeString(temporaryDirectory.resolve("result.json"), "{\"successful\":true}");

        EvidenceSecretScanner.Result result =
                EvidenceSecretScanner.scan(temporaryDirectory, java.util.Arrays.asList(null, "", " "));

        assertTrue(result.passed());
        assertTrue(result.findingPaths().isEmpty());
    }
}
