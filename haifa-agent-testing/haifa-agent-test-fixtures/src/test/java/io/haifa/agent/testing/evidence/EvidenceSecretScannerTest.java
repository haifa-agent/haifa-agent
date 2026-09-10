package io.haifa.agent.testing.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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

    @Test
    void acceptsInternalRelativeSymlinksWithoutFollowingEvidenceRootEscapes() throws Exception {
        Path target = Files.writeString(temporaryDirectory.resolve("node"), "safe evidence");
        Path link = temporaryDirectory.resolve("nodejs");
        createSymbolicLinkOrSkip(link, target.getFileName());

        EvidenceSecretScanner.Result result = EvidenceSecretScanner.scan(temporaryDirectory, List.of("not-present"));

        assertTrue(result.passed());
        assertTrue(result.findingPaths().isEmpty());
    }

    @Test
    void rejectsSymbolicLinksThatEscapeTheEvidenceRoot() throws Exception {
        Path evidenceRoot = Files.createDirectory(temporaryDirectory.resolve("evidence"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.txt"), "safe");
        Path link = evidenceRoot.resolve("escape");
        createSymbolicLinkOrSkip(link, Path.of("..").resolve(outside.getFileName()));

        assertThrows(IOException.class, () -> EvidenceSecretScanner.scan(evidenceRoot, List.of("safe")));
    }

    @Test
    void rejectsDanglingSymbolicLinks() throws Exception {
        Path link = temporaryDirectory.resolve("missing");
        createSymbolicLinkOrSkip(link, Path.of("not-created"));

        assertThrows(IOException.class, () -> EvidenceSecretScanner.scan(temporaryDirectory, List.of("safe")));
    }

    @Test
    void rejectsAbsoluteSymbolicLinkTargets() throws Exception {
        Path target = Files.writeString(temporaryDirectory.resolve("target.txt"), "safe");
        Path link = temporaryDirectory.resolve("absolute");
        createSymbolicLinkOrSkip(link, target.toAbsolutePath());

        assertThrows(IOException.class, () -> EvidenceSecretScanner.scan(temporaryDirectory, List.of("safe")));
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }
    }
}
