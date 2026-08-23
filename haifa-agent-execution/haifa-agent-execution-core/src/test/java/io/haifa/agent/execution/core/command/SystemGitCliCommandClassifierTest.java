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
        assertThat(classify("git for-each-ref '--format=%(upstream:short)' refs/heads/feat-delivery"))
                .isEqualTo(LOCAL_READ);
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
        assertThat(SystemGitCliCommandClassifier.classify("git add src/Main.java")
                        .reasonCode())
                .isEqualTo("GIT_STAGE");
        assertThat(SystemGitCliCommandClassifier.classify("git commit -m message")
                        .reasonCode())
                .isEqualTo("GIT_COMMIT");
        assertThat(SystemGitCliCommandClassifier.classify("gh pr create --base dev --title title")
                        .reasonCode())
                .isEqualTo("GH_PR_CREATE");
        assertThat(SystemGitCliCommandClassifier.classify("gh pr merge 42").reasonCode())
                .isEqualTo("GH_PR_MERGE_DENIED");
        assertThat(classify("gh pr merge 42")).isEqualTo(DENIED);
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
        assertThat(classify("gh pr merge 42 && echo done")).isEqualTo(DENIED);
        assertThat(classify("powershell -Command gh pr merge 42")).isEqualTo(DENIED);
        assertThat(classify("powershell -Command git status")).isEqualTo(UNKNOWN);
        assertThat(classify("git -C ../other status")).isEqualTo(DENIED);
        assertThat(classify("git -C nested status")).isEqualTo(DENIED);
        assertThat(classify("git --git-dir=.git status")).isEqualTo(DENIED);
        assertThat(classify("git --work-tree=.. status")).isEqualTo(DENIED);
        assertThat(classify("git --exec-path=tools status")).isEqualTo(DENIED);
        assertThat(classify("git --config-env=http.extraHeader=HEADER status")).isEqualTo(DENIED);
        assertThat(classify("env GH_TOKEN=value gh pr list")).isEqualTo(DENIED);
        assertThat(classify("$env:GH_TOKEN='value'; gh pr list")).isEqualTo(DENIED);
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
        assertThat(classify("alias inspect='git status'; inspect")).isEqualTo(UNKNOWN);
        assertThat(classify("(git status)")).isEqualTo(UNKNOWN);
        assertThat(classify("echo $(git status)")).isEqualTo(UNKNOWN);
        assertThat(classify("git status > status.txt")).isEqualTo(UNKNOWN);
        assertThat(classify("git status | more")).isEqualTo(UNKNOWN);
        assertThat(classify("git push origin --delete obsolete")).isEqualTo(DESTRUCTIVE);
        assertThat(classify("gh release delete v1")).isEqualTo(DESTRUCTIVE);
    }

    private static SystemGitCliCommandClassifier.Risk classify(String command) {
        return SystemGitCliCommandClassifier.classify(command).risk();
    }
}
