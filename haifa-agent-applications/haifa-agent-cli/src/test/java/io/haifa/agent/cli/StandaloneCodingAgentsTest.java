package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandaloneCodingAgentsTest {
    @TempDir
    Path root;

    @Test
    void publicFactoryReturnsTheProductClientAndSafeStableMetadata() throws Exception {
        Path firstWorkspace = Files.createDirectory(root.resolve("first"));
        Path secondWorkspace = Files.createDirectory(root.resolve("second"));

        StandaloneCodingAgentMetadata firstMetadata;
        try (StandaloneCodingAgent first = StandaloneCodingAgents.open(firstWorkspace)) {
            firstMetadata = first.metadata();
            assertThat(first.client()).isNotNull();
            assertThat(first.projectId()).isNotNull();
            assertThat(firstMetadata.providerId()).isEqualTo("deepseek");
            assertThat(firstMetadata.assemblyDigest()).matches("[0-9a-f]{64}");
            assertThat(first.toString())
                    .doesNotContain(firstWorkspace.toString())
                    .doesNotContain("DEEPSEEK_API_KEY")
                    .doesNotContain("api.deepseek.com");
        }

        try (StandaloneCodingAgent second = StandaloneCodingAgents.open(secondWorkspace)) {
            assertThat(second.metadata().assemblyDigest()).isEqualTo(firstMetadata.assemblyDigest());
        }
    }

    @Test
    void handleClosesIdempotentlyAndRejectsFurtherAccess() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("closed"));
        StandaloneCodingAgent agent = StandaloneCodingAgents.open(workspace);

        agent.close();
        agent.close();

        assertThatThrownBy(agent::client)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("standalone Coding Agent is closed");
        assertThatThrownBy(agent::projectId).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(agent::metadata).isInstanceOf(IllegalStateException.class);
        assertThat(agent.toString()).contains("closed=true");
    }

    @Test
    void publicEnvironmentMapIsUsedByTheResolvedProductAssembly() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("environment"));
        Path configuration = root.resolve("environment.yaml");
        Files.writeString(
                configuration,
                """
                tools:
                  enabled:
                    - file.list
                    - file.stat
                    - file.read
                    - file.search
                    - file.create
                    - file.write
                    - web.search
                web:
                  search:
                    enabled: true
                    provider: aliyun
                    credentialRef: env://EMBEDDED_WEB_KEY
                  fetch:
                    enabled: false
                """);

        try (StandaloneCodingAgent agent = StandaloneCodingAgents.open(
                workspace, configuration, Map.of("EMBEDDED_WEB_KEY", "embedded-test-credential"))) {
            assertThat(agent.client()).isNotNull();
            assertThat(agent.toString()).doesNotContain("embedded-test-credential");
        }
    }

    @Test
    void environmentInjectedCompatibleSkillIsValidatedDuringPublicAssembly() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("skill-workspace"));
        Path skillRoot = Files.createDirectory(root.resolve("skill-root"));
        Path skillPackage = Files.createDirectory(skillRoot.resolve("external-procedure"));
        Files.writeString(
                skillPackage.resolve("SKILL.md"),
                """
                ---
                name: external-procedure
                description: A reviewed external procedure.
                metadata:
                  external:
                    tags: [reviewed]
                ---
                # External procedure

                Follow the reviewed procedure.
                """);
        Path compatible = writeSkillConfiguration("compatible", "compatible");

        try (StandaloneCodingAgent agent = StandaloneCodingAgents.open(
                workspace, compatible, Map.of("EMBEDDED_SKILL_ROOT", skillRoot.toString()))) {
            assertThat(agent.client()).isNotNull();
        }

        Path strict = writeSkillConfiguration("strict", "strict");
        assertThatThrownBy(() -> StandaloneCodingAgents.open(
                        workspace, strict, Map.of("EMBEDDED_SKILL_ROOT", skillRoot.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configured allowed Skills are unavailable: external-procedure")
                .hasMessageContaining("SKILL_PARSE_FAILED");
    }

    private Path writeSkillConfiguration(String name, String parserMode) throws Exception {
        Path configuration = root.resolve(name + "-skill.yaml");
        Files.writeString(
                configuration,
                """
                skills:
                  allowed: [external-procedure]
                  localDirectories:
                    - id: reviewed-external-skills
                      root: ${EMBEDDED_SKILL_ROOT}
                      priority: 100
                      parserMode: %s
                      origin: imported
                """
                        .formatted(parserMode));
        return configuration;
    }
}
