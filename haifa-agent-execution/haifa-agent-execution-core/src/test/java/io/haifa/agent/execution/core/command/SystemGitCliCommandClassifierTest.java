package io.haifa.agent.execution.core.command;

import static io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier.Risk.DENIED;
import static io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier.Risk.DESTRUCTIVE;
import static io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier.Risk.EXTERNAL_WRITE;
import static io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier.Risk.LOCAL_READ;
import static io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier.Risk.LOCAL_WRITE;
import static io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier.Risk.NETWORK_READ;
import static io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier.Risk.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SystemGitCliCommandClassifierTest {
    @Test
    void classifiesDirectGitAndGithubCommandsByMinimumRisk() {
        assertThat(classify("git --no-pager status --short")).isEqualTo(LOCAL_READ);
        assertThat(classify("git --no-pager diff --no-color")).isEqualTo(LOCAL_READ);
        assertThat(classify("git grep needle")).isEqualTo(LOCAL_READ);
        assertThat(classify("git ls-files")).isEqualTo(LOCAL_READ);
        assertThat(classify("git add src/Main.java")).isEqualTo(LOCAL_WRITE);
        assertThat(classify("git fetch origin")).isEqualTo(LOCAL_WRITE);
        assertThat(classify("git ls-remote origin")).isEqualTo(NETWORK_READ);
        assertThat(classify("git push origin feature")).isEqualTo(EXTERNAL_WRITE);
        assertThat(classify("git push --force origin feature")).isEqualTo(DESTRUCTIVE);
        assertThat(classify("gh pr checks 42 --repo owner/repo")).isEqualTo(NETWORK_READ);
        assertThat(classify("gh issue comment 42 --body ok")).isEqualTo(EXTERNAL_WRITE);
        assertThat(classify("gh api repos/owner/repo")).isEqualTo(UNKNOWN);
        assertThat(classify("gh repo delete owner/repo")).isEqualTo(DESTRUCTIVE);
        assertThat(SystemGitCliCommandClassifier.classify("git diff --no-color").operation())
                .isEqualTo(SystemGitCliCommandClassifier.Operation.DIFF);
        assertThat(SystemGitCliCommandClassifier.classify("git status --short").operation())
                .isEqualTo(SystemGitCliCommandClassifier.Operation.INSPECT);
    }

    @Test
    void failsClosedForCompositionWrappersPathEscapesAndAuthenticationOverrides() {
        assertThat(classify("git status && git push")).isEqualTo(UNKNOWN);
        assertThat(classify("git -C nested status && echo done")).isEqualTo(DENIED);
        assertThat(classify("GH_TOKEN=value gh pr list && echo done")).isEqualTo(DENIED);
        assertThat(classify("env LANG=C GH_TOKEN=value gh pr list && echo done"))
                .isEqualTo(DENIED);
        assertThat(classify("C:\\tools\\gh.exe pr list && echo done")).isEqualTo(DENIED);
        assertThat(classify("\"C:\\tools\\gh.exe\" pr list && echo done")).isEqualTo(DENIED);
        assertThat(classify("git -c credential.helper=other status && echo done"))
                .isEqualTo(DENIED);
        assertThat(classify("git credential fill && echo done")).isEqualTo(DENIED);
        assertThat(classify("gh auth token && echo done")).isEqualTo(DENIED);
        assertThat(classify("gh auth status --show-token && echo done")).isEqualTo(DENIED);
        assertThat(classify("powershell -Command git status")).isEqualTo(UNKNOWN);
        assertThat(classify("git -C ../other status")).isEqualTo(DENIED);
        assertThat(classify("git -C nested status")).isEqualTo(DENIED);
        assertThat(classify("env GH_TOKEN=value gh pr list")).isEqualTo(DENIED);
        assertThat(classify("env LANG=C git status")).isEqualTo(UNKNOWN);
        assertThat(classify("LANG=C git status")).isEqualTo(UNKNOWN);
        assertThat(classify("git -c color.ui=false status")).isEqualTo(UNKNOWN);
        assertThat(classify(".\\git.exe status")).isEqualTo(DENIED);
        assertThat(classify("C:\\tools\\gh.exe pr list")).isEqualTo(DENIED);
        assertThat(classify("git diff --output=outside.patch")).isEqualTo(DENIED);
        assertThat(classify("$env:GH_CONFIG_DIR='other'; gh auth status")).isEqualTo(DENIED);
        assertThat(classify("gh auth token")).isEqualTo(DENIED);
        assertThat(classify("gh auth status --show-token")).isEqualTo(DENIED);
        assertThat(classify("git credential fill")).isEqualTo(DENIED);
        assertThat(classify("echo 'GH_TOKEN=value' && git status")).isEqualTo(DENIED);
    }

    private static SystemGitCliCommandClassifier.Risk classify(String command) {
        return SystemGitCliCommandClassifier.classify(command).risk();
    }
}
