package io.haifa.agent.personalassistant.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.server.configuration.health.PersonalAssistantHealth;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * An OPTIONAL MCP server that is not running must never block product startup: PA still starts and reports health.
 */
@Tag("slow")
class PersonalAssistantMcpDegradationTest {
    @Test
    void unavailableOptionalMcpServerKeepsProductStartupHealthy() throws IOException {
        Path data = Files.createTempDirectory("haifa-personal-mcp-degraded-");
        int unavailablePort = unusedPort();
        String[] arguments = {
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--spring.config.location=classpath:/application-deterministic-model.yml",
            "--haifa.personal.data-directory=" + data,
            "--haifa.personal.continuation-key-base64="
                    + Base64.getEncoder().encodeToString(new byte[32]),
            "--haifa.personal.mcp.mode=external",
            "--haifa.personal.mcp.endpoint=http://127.0.0.1:" + unavailablePort + "/mcp",
            "--haifa.personal.mcp.allowed-tools=echo",
            "--haifa.personal.mcp.required=false"
        };

        try (ConfigurableApplicationContext context =
                new SpringApplication(PersonalAssistantServerApplication.class).run(arguments)) {
            assertThat(context.getBean(PersonalAssistantApplication.class)).isNotNull();
            assertThat(context.getBean(PersonalAssistantHealth.class).health().getStatus())
                    .isEqualTo(Status.UP);
        }
    }

    private static int unusedPort() throws IOException {
        for (int attempt = 0; attempt < 20; attempt++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                int port = socket.getLocalPort();
                if (port >= 20002) {
                    return port;
                }
            }
        }
        throw new IllegalStateException("no unused loopback port above the reserved MCP range");
    }
}
