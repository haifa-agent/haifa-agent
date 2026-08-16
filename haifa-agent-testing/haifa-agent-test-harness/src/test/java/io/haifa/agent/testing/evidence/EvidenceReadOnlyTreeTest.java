package io.haifa.agent.testing.evidence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.haifa.agent.common.io.SecureFilePermissions;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceReadOnlyTreeTest {
    @TempDir
    Path temporary;

    @Test
    void appliesAndVerifiesTheHostReadOnlyBaseline() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("evidence"));
        Path nested = Files.createDirectory(root.resolve("nested"));
        Path evidence = Files.writeString(nested.resolve("result.json"), "{}\n");
        try {
            EvidenceReadOnlyTree.apply(root);
            EvidenceReadOnlyTree.apply(root);

            assertTrue(EvidenceReadOnlyTree.isReadOnly(evidence));
            assertTrue(EvidenceReadOnlyTree.isReadOnly(nested));
            assertTrue(EvidenceReadOnlyTree.isReadOnly(root));
        } finally {
            SecureFilePermissions.secureFile(evidence);
            SecureFilePermissions.secureDirectory(nested);
            SecureFilePermissions.secureDirectory(root);
        }
    }

    @Test
    void finalizesAStableManifestAndMakesTheWholeEvidenceTreeReadOnly() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("final-evidence"));
        Path nested = Files.createDirectory(root.resolve("nested"));
        Path evidence = Files.writeString(nested.resolve("result.json"), "{}\n");
        Path link = nested.resolve("result-link.json");
        createSymbolicLinkOrSkip(link, evidence.getFileName());
        Path desktopMetadata = Files.writeString(root.resolve(".DS_Store"), "mutable metadata");
        Path symlinkMetadata = root.resolve(".evidence-symlinks-v1.json");
        Path manifest = root.resolve("manifest.sha256");
        try {
            EvidenceFinalizer.finalizeEvidence(root);

            var manifestLines = Files.readAllLines(manifest);
            assertTrue(manifestLines.stream().anyMatch(line -> line.endsWith("  nested/result.json")));
            assertTrue(manifestLines.stream().anyMatch(line -> line.endsWith("  .evidence-symlinks-v1.json")));
            assertFalse(manifestLines.stream().anyMatch(line -> line.endsWith("  nested/result-link.json")));
            assertFalse(manifestLines.stream().anyMatch(line -> line.endsWith("  .DS_Store")));
            assertTrue(Files.isSymbolicLink(link));
            assertTrue(Files.readString(symlinkMetadata).contains("result-link.json"));
            assertTrue(EvidenceReadOnlyTree.isReadOnly(evidence));
            assertTrue(EvidenceReadOnlyTree.isReadOnly(manifest));
            assertTrue(EvidenceReadOnlyTree.isReadOnly(root));
        } finally {
            SecureFilePermissions.secureFile(evidence);
            SecureFilePermissions.secureFile(desktopMetadata);
            if (Files.exists(manifest)) {
                SecureFilePermissions.secureFile(manifest);
            }
            if (Files.exists(symlinkMetadata)) {
                SecureFilePermissions.secureFile(symlinkMetadata);
            }
            SecureFilePermissions.secureDirectory(nested);
            SecureFilePermissions.secureDirectory(root);
        }
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }
    }
}
