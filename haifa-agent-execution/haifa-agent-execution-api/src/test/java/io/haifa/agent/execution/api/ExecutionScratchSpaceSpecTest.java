package io.haifa.agent.execution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExecutionScratchSpaceSpecTest {
    @Test
    void rejectsUnsafeLogicalChildrenAndSensitiveEnvironmentNames() {
        for (String path : List.of("/absolute", "../escape", "safe//empty", "safe/../escape", "nul\u0000byte")) {
            assertThatThrownBy(() -> new ExecutionScratchBinding("CACHE_DIR", path))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String name : List.of("HTTP_PROXY", "SSH_AUTH_SOCK", "SERVICE_TOKEN", "API_KEY")) {
            assertThatThrownBy(() -> new ExecutionScratchBinding(name, "cache"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void invocationIdentityChangesWithTheScratchContract() {
        var generic = ExecutionScratchSpaceSpec.genericRequired();
        var coding = new ExecutionScratchSpaceSpec(
                true,
                Set.of("TMPDIR", "TMP", "TEMP", "GOTMPDIR"),
                List.of(new ExecutionScratchBinding("GOCACHE", "go-build")));
        String base = "a".repeat(64);

        assertThat(generic.canonicalDigest()).hasSize(64).isNotEqualTo(coding.canonicalDigest());
        assertThat(ExecutionRequest.digestWithScratch(base, generic))
                .isNotEqualTo(ExecutionRequest.digestWithScratch(base, coding));
    }
}
