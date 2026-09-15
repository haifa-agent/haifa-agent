package io.haifa.agent.application.project.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodingExecutionToolRequestCanonicalizerTest {
    @Test
    void normalizesLogicalRelativePathsWithoutMappingHostAbsolutePaths() {
        assertThat(CodingExecutionToolRequestCanonicalizer.canonicalizeRelativeWorkdir("src\\test"))
                .isEqualTo("src/test");
        assertThat(CodingExecutionToolRequestCanonicalizer.canonicalizeRelativeWorkdir("/app"))
                .isEqualTo("/app");
    }

    @Test
    void preservesTargetsThatMustBeRejectedByTheExecutionBoundary() {
        assertThat(CodingExecutionToolRequestCanonicalizer.canonicalizeRelativeWorkdir("/outside"))
                .isEqualTo("/outside");
        assertThat(CodingExecutionToolRequestCanonicalizer.canonicalizeRelativeWorkdir("../outside"))
                .isEqualTo("../outside");
    }

    @Test
    void canonicalizationIsIdempotent() {
        String child = CodingExecutionToolRequestCanonicalizer.canonicalizeRelativeWorkdir("src\\main");

        assertThat(CodingExecutionToolRequestCanonicalizer.canonicalizeRelativeWorkdir(child))
                .isEqualTo(child)
                .isEqualTo("src/main");
    }

    @Test
    void canonicalizesAbsoluteTargetPathAndPreservesRelativeTarget() {
        String currentDir =
                java.nio.file.Path.of(".").toAbsolutePath().normalize().toString();
        String redundantAbsolute = currentDir + java.io.File.separator + "sub" + java.io.File.separator + ".."
                + java.io.File.separator + "target";
        String expectedNormalized = currentDir + java.io.File.separator + "target";

        assertThat(CodingExecutionToolRequestCanonicalizer.canonicalizeTargetPath("  " + redundantAbsolute + "  "))
                .isEqualTo(expectedNormalized);
        assertThat(CodingExecutionToolRequestCanonicalizer.canonicalizeTargetPath("  relative/path  "))
                .isEqualTo("relative/path");
    }
}
