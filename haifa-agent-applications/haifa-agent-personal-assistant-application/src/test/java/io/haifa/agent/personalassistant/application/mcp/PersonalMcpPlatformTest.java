package io.haifa.agent.personalassistant.application.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * OPTIONAL MCP capability must degrade without failing product startup, while a REQUIRED capability still fails closed.
 */
class PersonalMcpPlatformTest {
    private static final TenantRef TENANT = new TenantRef("local");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("public-user", "user");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void disabledConfigurationExposesNoMcpCapability() {
        try (PersonalMcpPlatform platform = PersonalMcpPlatform.connect(null, TENANT, PRINCIPAL, CLOCK)) {
            assertThat(platform.available()).isFalse();
            assertThat(platform.state()).isEqualTo("disabled");
            assertThat(platform.aliases()).isEmpty();
            assertThat(platform.contributions()).isEmpty();
        }
    }

    @Test
    void optionalUnavailableServerDegradesInsteadOfFailingStartup() throws IOException {
        try (PersonalMcpPlatform platform =
                PersonalMcpPlatform.connect(configuration(false), TENANT, PRINCIPAL, CLOCK)) {
            assertThat(platform.available()).isFalse();
            assertThat(platform.state()).isEqualTo("unavailable");
            assertThat(platform.degradationReason()).isNotBlank();
            assertThat(platform.aliases()).isEmpty();
            assertThat(platform.contributions()).isEmpty();
        }
    }

    @Test
    void requiredUnavailableServerStillFailsClosed() throws IOException {
        PersonalMcpConfiguration configuration = configuration(true);
        assertThatThrownBy(() -> PersonalMcpPlatform.connect(configuration, TENANT, PRINCIPAL, CLOCK))
                .isInstanceOf(RuntimeException.class);
    }

    private static PersonalMcpConfiguration configuration(boolean required) throws IOException {
        return new PersonalMcpConfiguration(
                URI.create("http://127.0.0.1:" + unusedPort() + "/mcp"),
                "personal-local",
                "Personal MCP",
                Set.of("echo"),
                "personal_mcp",
                required);
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
