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
}
