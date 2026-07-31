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
        Path desktopMetadata = Files.writeString(root.resolve(".DS_Store"), "mutable metadata");
        Path manifest = root.resolve("manifest.sha256");
        try {
            EvidenceFinalizer.finalizeEvidence(root);

            var manifestLines = Files.readAllLines(manifest);
            assertTrue(manifestLines.stream().anyMatch(line -> line.endsWith("  nested/result.json")));
            assertFalse(manifestLines.stream().anyMatch(line -> line.endsWith("  .DS_Store")));
            assertTrue(EvidenceReadOnlyTree.isReadOnly(evidence));
            assertTrue(EvidenceReadOnlyTree.isReadOnly(manifest));
            assertTrue(EvidenceReadOnlyTree.isReadOnly(root));
        } finally {
            SecureFilePermissions.secureFile(evidence);
            SecureFilePermissions.secureFile(desktopMetadata);
            if (Files.exists(manifest)) {
                SecureFilePermissions.secureFile(manifest);
            }
            SecureFilePermissions.secureDirectory(nested);
            SecureFilePermissions.secureDirectory(root);
        }
    }
}
