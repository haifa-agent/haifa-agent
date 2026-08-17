package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXException;

class MavenTestEvidenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyExecutedPassingFailsafeEvidence() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("TEST-example.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="ExampleIT" tests="2" failures="0" errors="0" skipped="0"/>
                """);

        MavenTestEvidence evidence = MavenTestEvidence.inspect(temporaryDirectory);

        assertEquals(1, evidence.reportFiles());
        assertEquals(2, evidence.tests());
        assertEquals(64, evidence.reports().getFirst().sha256().length());
        assertTrue(evidence.passing());
        assertEquals(MavenTestEvidence.Status.PASSED, evidence.status(true, 0, true));
    }

    @Test
    void rejectsMissingAndSkippedExecutionEvidence() throws Exception {
        MavenTestEvidence missing = MavenTestEvidence.inspect(temporaryDirectory.resolve("missing"));
        assertFalse(missing.passing());
        assertEquals(MavenTestEvidence.Status.NOT_RUN, missing.status(true, 0, true));

        Files.writeString(
                temporaryDirectory.resolve("TEST-skipped.xml"),
                """
                <testsuite name="SkippedLiveIT" tests="1" failures="0" errors="0" skipped="1"/>
                """);
        MavenTestEvidence skipped = MavenTestEvidence.inspect(temporaryDirectory);

        assertEquals(1, skipped.skipped());
        assertFalse(skipped.passing());
        assertEquals(MavenTestEvidence.Status.SKIPPED, skipped.status(true, 0, true));
        assertEquals(MavenTestEvidence.Status.TIMEOUT, skipped.status(false, 124, true));
    }

    @Test
    void rejectsDoctypeBearingReports() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("TEST-unsafe.xml"),
                """
                <!DOCTYPE testsuite [<!ENTITY xxe SYSTEM "file:///unavailable">]>
                <testsuite name="UnsafeIT" tests="1" failures="0" errors="0" skipped="0">&xxe;</testsuite>
                """);

        assertThrows(SAXException.class, () -> MavenTestEvidence.inspect(temporaryDirectory));
    }

    @Test
    void removesRawReportsAfterSafeEvidenceWasCaptured() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("TEST-example.xml"),
                """
                <testsuite name="ExampleIT" tests="1" failures="0" errors="0" skipped="0"/>
                """);
        MavenTestEvidence evidence = MavenTestEvidence.inspect(temporaryDirectory);

        MavenTestEvidence.deleteRawReports(temporaryDirectory);

        assertTrue(evidence.passing());
        assertFalse(Files.exists(temporaryDirectory));
    }
}
