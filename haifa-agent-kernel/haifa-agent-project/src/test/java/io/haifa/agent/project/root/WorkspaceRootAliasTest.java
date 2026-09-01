package io.haifa.agent.project.root;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceRootAliasTest {

    @Test
    void mainConstantHasCorrectValue() {
        assertThat(WorkspaceRootAlias.MAIN.value()).isEqualTo("main");
        assertThat(WorkspaceRootAlias.MAIN.isMain()).isTrue();
        assertThat(WorkspaceRootAlias.of("main")).isSameAs(WorkspaceRootAlias.MAIN);
    }

    @Test
    void validAliasesAreAccepted() {
        List<String> valid = List.of("docs", "config", "test-root", "module_1", "A", "b", "c123");
        for (String value : valid) {
            WorkspaceRootAlias alias = WorkspaceRootAlias.of(value);
            assertThat(alias.value()).isEqualTo(value);
            assertThat(alias.toString()).isEqualTo(value);
        }
    }

    @Test
    void invalidAliasesAreRejected() {
        List<String> invalid = List.of("", "   ", "docs/sub", "a:b", "a.b", "@special", "this-alias-is-way-too-long-exceeding-thirty-two-chars");
        for (String value : invalid) {
            assertThatThrownBy(() -> WorkspaceRootAlias.of(value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void nullAliasIsRejected() {
        assertThatThrownBy(() -> new WorkspaceRootAlias(null))
                .isInstanceOf(NullPointerException.class);
    }
}
