package io.haifa.agent.application.project.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class CodingExecutionToolRequestCanonicalizerTest {
    @Test
    void mapsTheWorkspaceRootAndNormalizesLogicalRelativePaths() {
        UnaryOperator<String> workspace = value -> switch (value) {
            case "/app" -> ".";
            case "/app/src/main" -> "src/main";
            default -> value;
        };

        assertThat(CodingExecutionToolRequestCanonicalizer.canonicalizeWorkdir("/app", workspace))
                .isEqualTo(".");
        assertThat(CodingExecutionToolRequestCanonicalizer.canonicalizeWorkdir("/app/src/main", workspace))
                .isEqualTo("src/main");
        assertThat(CodingExecutionToolRequestCanonicalizer.canonicalizeWorkdir("src\\test", workspace))
                .isEqualTo("src/test");
    }

    @Test
    void preservesTargetsThatMustBeRejectedByTheExecutionBoundary() {
        UnaryOperator<String> workspace = UnaryOperator.identity();

        assertThat(CodingExecutionToolRequestCanonicalizer.canonicalizeWorkdir("/outside", workspace))
                .isEqualTo("/outside");
        assertThat(CodingExecutionToolRequestCanonicalizer.canonicalizeWorkdir("../outside", workspace))
                .isEqualTo("../outside");
    }
}
