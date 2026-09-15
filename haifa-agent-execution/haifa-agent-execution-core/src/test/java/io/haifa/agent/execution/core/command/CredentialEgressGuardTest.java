package io.haifa.agent.execution.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CredentialEgressGuardTest {

    @Test
    void rejectsConfirmedHostAuthenticationEnvironmentOverrides() {
        for (String command : new String[] {
            "GH_TOKEN=value gh pr list",
            "env LANG=C GITHUB_TOKEN=value gh repo view",
            "GIT_ASKPASS=/tmp/echo-pass.sh git fetch origin",
            "SSH_ASKPASS=script SSH_AUTH_SOCK=/tmp/agent.sock git push",
            "$env:GH_CONFIG_DIR='other'; gh auth status",
            "set HOME=C:\\other && git status",
            "GIT_CONFIG_GLOBAL=/tmp/other.gitconfig git config --get user.email"
        }) {
            assertThat(CredentialEgressGuard.rejectionCode(command))
                    .as(command)
                    .contains(CredentialEgressGuard.ENVIRONMENT_OVERRIDE_CODE);
        }
    }

    @Test
    void rejectsCredentialProtocolAndConfigOverridePaths() {
        for (String command : new String[] {
            "git credential fill",
            "git credential approve",
            "git credential-cache get",
            "git -c credential.helper=other status",
            "git -c http.extraHeader=AUTHORIZATION: secret status",
            "git -c core.sshCommand=/tmp/steal status",
            "git --config-env=credential.helper=SUPPLIED status",
            "git --config-env=http.extraHeader=SUPPLIED status"
        }) {
            assertThat(CredentialEgressGuard.rejectionCode(command)).as(command).isPresent();
        }
    }

    @Test
    void rejectsGithubTokenDisclosureAndAuthenticationStateMutation() {
        assertThat(CredentialEgressGuard.rejectionCode("gh auth token"))
                .contains(CredentialEgressGuard.GITHUB_AUTH_STATE_CODE);
        assertThat(CredentialEgressGuard.rejectionCode("gh auth status --show-token"))
                .contains(CredentialEgressGuard.GITHUB_TOKEN_DISCLOSURE_CODE);
        assertThat(CredentialEgressGuard.rejectionCode("gh auth login"))
                .contains(CredentialEgressGuard.GITHUB_AUTH_STATE_CODE);
        assertThat(CredentialEgressGuard.rejectionCode("gh auth logout"))
                .contains(CredentialEgressGuard.GITHUB_AUTH_STATE_CODE);
        assertThat(CredentialEgressGuard.rejectionCode("gh auth setup-git"))
                .contains(CredentialEgressGuard.GITHUB_AUTH_STATE_CODE);
    }

    @Test
    void allowsOrdinaryGitGithubAndCustomerCommandsThroughTheGenericPath() {
        for (String command : new String[] {
            "git status",
            "git -C docs status",
            "git -c color.ui=false rev-parse HEAD",
            "git --git-dir=.git status",
            "git --work-tree=.. status",
            "git fetch origin",
            "git push origin feature",
            "git reset --hard HEAD",
            "gh pr view 42",
            "gh pr merge 42",
            "gh auth status",
            "gh repo delete owner/repo",
            "GH_TOKEN_PREFIX=value gh pr list",
            "mvn test && echo done",
            "cd docs && git status"
        }) {
            assertThat(CredentialEgressGuard.rejectionCode(command)).as(command).isEmpty();
        }
    }

    @Test
    void rejectsWithoutEchoingTheProtectedValueIntoTheReasonCode() {
        String secret = "ghp_super_secret_token_value";
        String command = "GH_TOKEN=" + secret + " gh pr list";

        String code = CredentialEgressGuard.rejectionCode(command).orElseThrow();
        assertThat(code).doesNotContain(secret).doesNotContain("<").doesNotContain(">");
    }
}
