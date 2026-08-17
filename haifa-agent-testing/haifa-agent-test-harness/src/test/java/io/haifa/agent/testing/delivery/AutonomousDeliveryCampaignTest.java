package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.haifa.agent.testing.evidence.Sha256Digests;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutonomousDeliveryCampaignTest {
    @TempDir
    Path temporary;

    @Test
    void historicalDigestIgnoresOnlyFinderDesktopMetadata() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("evidence"));
        Files.writeString(evidence.resolve("result.json"), "{}");
        String before = Sha256Digests.historicalEvidenceDirectory(evidence);

        Files.writeString(evidence.resolve(".DS_Store"), "mutable desktop metadata");

        assertEquals(before, Sha256Digests.historicalEvidenceDirectory(evidence));
    }

    @Test
    void evidenceManifestExcludesMutableFinderDesktopMetadata() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("manifest-evidence"));
        Files.writeString(evidence.resolve("result.json"), "{}");
        Files.writeString(evidence.resolve(".DS_Store"), "initial desktop metadata");

        io.haifa.agent.testing.evidence.EvidenceFinalizer.writeManifest(evidence);
        List<String> manifest = Files.readAllLines(evidence.resolve("manifest.sha256"));
        Files.writeString(evidence.resolve(".DS_Store"), "changed desktop metadata");

        assertTrue(manifest.stream().anyMatch(line -> line.endsWith("  result.json")));
        assertFalse(manifest.stream().anyMatch(line -> line.endsWith("  .DS_Store")));
    }
}
