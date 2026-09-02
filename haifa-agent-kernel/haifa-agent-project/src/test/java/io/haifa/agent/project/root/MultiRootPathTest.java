package io.haifa.agent.project.root;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MultiRootPathTest {

    @Test
    void ofMainNormalizesRelativePath() {
        MultiRootPath path = MultiRootPath.ofMain("src/App.java");
        assertThat(path.rootAlias()).isEqualTo(WorkspaceRootAlias.MAIN);
        assertThat(path.relativePath()).isEqualTo("src/App.java");
        assertThat(path.isRoot()).isFalse();
        assertThat(path.toString()).isEqualTo("main:src/App.java");
    }

    @Test
    void ofMainWithRootOrEmptyPath() {
        MultiRootPath rootPath1 = MultiRootPath.ofMain(".");
        assertThat(rootPath1.relativePath()).isEmpty();
        assertThat(rootPath1.isRoot()).isTrue();
        assertThat(rootPath1.toString()).isEqualTo("main:.");

        MultiRootPath rootPath2 = MultiRootPath.ofMain("");
        assertThat(rootPath2.relativePath()).isEmpty();
        assertThat(rootPath2.isRoot()).isTrue();

        MultiRootPath rootPath3 = MultiRootPath.ofMain("/./");
        assertThat(rootPath3.relativePath()).isEmpty();
        assertThat(rootPath3.isRoot()).isTrue();
    }

    @Test
    void ofCustomAliasNormalizesWindowsSeparators() {
        MultiRootPath path = MultiRootPath.of(WorkspaceRootAlias.of("docs"), "guide\\getting-started.md");
        assertThat(path.rootAlias().value()).isEqualTo("docs");
        assertThat(path.relativePath()).isEqualTo("guide/getting-started.md");
        assertThat(path.toString()).isEqualTo("docs:guide/getting-started.md");
    }
}
