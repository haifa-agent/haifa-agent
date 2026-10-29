package io.haifa.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DockerDesktopPathsTest {
    @Test
    void driveMountsAcceptOnlyExactDesktopAliases() {
        String root = "D:\\work space\\项目";
        assertThat(DockerDesktopPaths.bindSource(root)).isEqualTo("/d/work space/项目");
        for (String actual : java.util.List.of(root, "D:/work space/项目", "/d/work space/项目",
                "/host_mnt/d/work space/项目", "/run/desktop/mnt/host/d/work space/项目")) {
            assertThat(DockerDesktopPaths.sameMount(root, actual)).isTrue();
        }
        for (String actual : java.util.List.of("/d/work space/项目-other", "/e/work space/项目",
                "/d/Work space/项目", "/host_mnt/other/d/work space/项目")) {
            assertThat(DockerDesktopPaths.sameMount(root, actual)).isFalse();
        }
        assertThat(DockerDesktopPaths.sameMount(root, null)).isFalse();
    }

    @Test
    void posixMountIdentityStaysExactAndUncMountsAreUnsupported() {
        assertThat(DockerDesktopPaths.bindSource("/srv/workspace")).isEqualTo("/srv/workspace");
        assertThat(DockerDesktopPaths.sameMount("/srv/workspace", "/srv/workspace")).isTrue();
        assertThat(DockerDesktopPaths.sameMount("/srv/workspace", "/host_mnt/srv/workspace")).isFalse();
        assertThatThrownBy(() -> DockerDesktopPaths.bindSource("\\\\server\\workspace"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
