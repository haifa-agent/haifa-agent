package io.haifa.agent.testing.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.haifa.agent.testing.evidence.Sha256Digests;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunnerArtifactTest {
    private static final String DIGEST = "a".repeat(64);

    @Test
    void roundTripsTheReviewedIdentityAndRejectsMismatch() {
        RunnerArtifact artifact = new RunnerArtifact(1, "runner.jar", DIGEST, RunnerArtifact.MAIN_CLASS);

        assertEquals(artifact, RunnerArtifact.fromReviewedInput(artifact.reviewedInput()));
        artifact.requireCurrent(new RunnerArtifact(1, "runner.jar", DIGEST, RunnerArtifact.MAIN_CLASS));
        assertThrows(
                IllegalArgumentException.class,
                () -> artifact.requireCurrent(
                        new RunnerArtifact(1, "runner.jar", "b".repeat(64), RunnerArtifact.MAIN_CLASS)));
    }

    @Test
    void rejectsPathsAndUnpackagedCodeSources() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RunnerArtifact(1, "../runner.jar", DIGEST, RunnerArtifact.MAIN_CLASS));
        assertThrows(IllegalArgumentException.class, RunnerArtifact::current);
    }

    @Test
    void hashesTheExactPackagedArtifact(@TempDir Path temporaryDirectory) throws Exception {
        Path runner = temporaryDirectory.resolve("runner.jar");
        Files.writeString(runner, "runner bytes");

        RunnerArtifact artifact = RunnerArtifact.fromPath(runner);

        assertEquals("runner.jar", artifact.artifactName());
        assertEquals(Sha256Digests.file(runner), artifact.sha256());
    }
}
