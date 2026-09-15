package io.haifa.agent.execution.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CredentialEgressGuardTest {

    @Test
    void rejectsConfirmedHostAuthenticationEnvironmentOverrides() {
        for (String command : new String[] {
            "GH_TOKEN=value gh pr list",
            "HOME=/tmp git status",
            "env LANG=C GITHUB_TOKEN=value gh repo view",
            "env -i HOME=/tmp git status",
            "export GH_TOKEN=value",
            "GIT_ASKPASS=/tmp/echo-pass.sh git fetch origin",
            "SSH_ASKPASS=script SSH_AUTH_SOCK=/tmp/agent.sock git push",
            "FOO=1 GIT_CONFIG_SYSTEM=/etc/other git status",
            "$env:GH_CONFIG_DIR='other'; gh auth status",
            "$env:GH_TOKEN = 'value'; gh pr list",
            "set HOME=C:\\other && git status",
            "set \"GH_TOKEN=value\"",
            "SET USERPROFILE=C:\\other",
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
            "/usr/bin/git credential fill",
            "git --no-pager credential fill",
            "git -c color.ui=false credential fill",
            "git --no-pager credential-cache get",
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
        assertThat(CredentialEgressGuard.rejectionCode("gh auth status -t"))
                .contains(CredentialEgressGuard.GITHUB_TOKEN_DISCLOSURE_CODE);
        assertThat(CredentialEgressGuard.rejectionCode("gh auth status --show-token=true"))
                .contains(CredentialEgressGuard.GITHUB_TOKEN_DISCLOSURE_CODE);
        assertThat(CredentialEgressGuard.rejectionCode("gh --hostname github.com auth token"))
                .contains(CredentialEgressGuard.GITHUB_AUTH_STATE_CODE);
        assertThat(CredentialEgressGuard.rejectionCode("gh auth login"))
                .contains(CredentialEgressGuard.GITHUB_AUTH_STATE_CODE);
        assertThat(CredentialEgressGuard.rejectionCode("gh auth logout"))
                .contains(CredentialEgressGuard.GITHUB_AUTH_STATE_CODE);
        assertThat(CredentialEgressGuard.rejectionCode("gh auth setup-git"))
                .contains(CredentialEgressGuard.GITHUB_AUTH_STATE_CODE);
    }

    @Test
    void allowsTextualAssignmentsAndEchoedCredentialLiteralsThroughTheGenericPath() {
        for (String command : new String[] {
            "echo \"HOME=value\"",
            "Write-Output 'GH_TOKEN=value'",
            "echo HOME=value",
            "printf 'HOME=%s' value",
            "grep -rn HOME= .",
            "echo \"set HOME=value\"",
            "Write-Output '$env:GH_TOKEN=value'",
            "git log --format=HOME=%h",
            "echo \"git credential fill\"",
            "echo \"gh auth token\"",
            "echo \"git -c credential.helper=other status\""
        }) {
            assertThat(CredentialEgressGuard.rejectionCode(command)).as(command).isEmpty();
        }
    }

    @Test
    void allowsOrdinaryGitGithubAndCustomerCommandsThroughTheGenericPath() {
        for (String command : new String[] {
            "git status",
            "git -C docs status",
            "git -c color.ui=false rev-parse HEAD",
            "git --git-dir=.git status",
            "git --work-tree=.. status",
            "git --no-pager status",
            "git grep -c credential.helper -- .",
            "git fetch origin",
            "git push origin feature",
            "git reset --hard HEAD",
            "gh pr view 42",
            "gh pr merge 42",
            "gh auth status",
            "gh --hostname github.com pr view 42",
            "gh repo delete owner/repo",
            "GH_TOKEN_PREFIX=value gh pr list",
            "mvn test && echo done",
            "cd docs && git status"
        }) {
            assertThat(CredentialEgressGuard.rejectionCode(command)).as(command).isEmpty();
        }
    }

    @Test
    void allowsEnvLaunchedCommandsWithBenignAssignmentLikeArguments() {
        for (String command : new String[] {
            "env echo HOME=value",
            "env printenv GH_TOKEN=value",
            "env -i echo GIT_ASKPASS=/tmp/pass.sh",
            "env FOO=1 echo HOME=value"
        }) {
            assertThat(CredentialEgressGuard.rejectionCode(command)).as(command).isEmpty();
        }
    }

    @Test
    void documentsTheKnownLimitsOfTheLeadingTokenContract() {
        for (String command : new String[] {
            "echo starting && HOME=/tmp git status", "bash -c 'git credential fill'", "sh -c \"gh auth token\""
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
