package io.haifa.agent.sdk.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class JavaToolSpecTest {

    private record Request(String value) {}

    private record Response(String value) {}

    @Test
    void pureToolsDeclareLowRiskNoApprovalAndPureIdempotency() {
        JavaToolSpec<Request, Response> spec = JavaToolSpec.builder("weather_get", Request.class, Response.class)
                .description("Gets the weather")
                .pure()
                .build();

        assertThat(spec.risk()).isEqualTo(ToolRisk.LOW);
        assertThat(spec.idempotency()).isEqualTo(ToolIdempotency.PURE);
        assertThat(spec.approvalRequirement()).isEqualTo(ToolApprovalRequirement.NEVER);
        assertThat(spec.sideEffects()).isEmpty();
    }

    @Test
    void undeclaredToolsKeepConservativeDefaults() {
        JavaToolSpec<Request, Response> spec = JavaToolSpec.builder("weather_get", Request.class, Response.class)
                .build();

        assertThat(spec.risk()).isEqualTo(ToolRisk.MEDIUM);
        assertThat(spec.idempotency()).isEqualTo(ToolIdempotency.UNKNOWN);
        assertThat(spec.approvalRequirement()).isEqualTo(ToolApprovalRequirement.POLICY);
    }

    @Test
    void declaringSideEffectsDropsThePureDeclaration() {
        JavaToolSpec<Request, Response> spec = JavaToolSpec.builder("weather_get", Request.class, Response.class)
                .pure()
                .sideEffects(ToolSideEffect.FILE_WRITE)
                .build();

        assertThat(spec.sideEffects()).containsExactly(ToolSideEffect.FILE_WRITE);
        assertThat(spec.pure()).isFalse();
        assertThat(spec.risk()).isEqualTo(ToolRisk.MEDIUM);
        assertThat(spec.approvalRequirement()).isEqualTo(ToolApprovalRequirement.POLICY);
    }

    @Test
    void pureDeclarationClearsAnyDeclaredSideEffect() {
        JavaToolSpec<Request, Response> spec = JavaToolSpec.builder("weather_get", Request.class, Response.class)
                .sideEffects(ToolSideEffect.FILE_WRITE)
                .pure()
                .build();

        assertThat(spec.sideEffects()).isEmpty();
        assertThat(spec.pure()).isTrue();
        assertThat(spec.approvalRequirement()).isEqualTo(ToolApprovalRequirement.NEVER);
    }

    @Test
    void rejectsNetworkAccessBecauseJavaToolsCannotConstrainHosts() {
        assertThatThrownBy(() -> JavaToolSpec.builder("weather_get", Request.class, Response.class)
                        .sideEffects(ToolSideEffect.NETWORK_ACCESS)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NETWORK_ACCESS");
    }

    @Test
    void networkAccessDeclaresExactHostsNetworkSideEffectAndConservativeDefaults() {
        JavaToolSpec<Request, Response> spec = JavaToolSpec.builder("web_fetch", Request.class, Response.class)
                .description("Fetches a URL")
                .networkAccess("API.Example.com", "cdn.example.com")
                .build();

        assertThat(spec.networkHosts()).containsExactlyInAnyOrder("api.example.com", "cdn.example.com");
        assertThat(spec.sideEffects()).containsExactly(ToolSideEffect.NETWORK_ACCESS);
        assertThat(spec.pure()).isFalse();
        assertThat(spec.risk()).isEqualTo(ToolRisk.MEDIUM);
        assertThat(spec.idempotency()).isEqualTo(ToolIdempotency.UNKNOWN);
        assertThat(spec.approvalRequirement()).isEqualTo(ToolApprovalRequirement.POLICY);
    }

    @Test
    void networkAccessFailsClosedForMissingBlankWildcardAndInvalidHosts() {
        assertThatThrownBy(() -> JavaToolSpec.builder("web_fetch", Request.class, Response.class)
                        .networkAccess())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one host");
        assertThatThrownBy(() -> JavaToolSpec.builder("web_fetch", Request.class, Response.class)
                        .networkAccess(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> JavaToolSpec.builder("web_fetch", Request.class, Response.class)
                        .networkAccess("*.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wildcard");
        assertThatThrownBy(() -> JavaToolSpec.builder("web_fetch", Request.class, Response.class)
                        .networkAccess("https://example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
        assertThatThrownBy(() -> JavaToolSpec.builder("web_fetch", Request.class, Response.class)
                        .networkAccess("example.com/path"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void pureClearsTheNetworkDeclaration() {
        JavaToolSpec<Request, Response> spec = JavaToolSpec.builder("web_fetch", Request.class, Response.class)
                .networkAccess("api.example.com")
                .pure()
                .build();

        assertThat(spec.networkHosts()).isEmpty();
        assertThat(spec.sideEffects()).isEmpty();
        assertThat(spec.pure()).isTrue();
    }

    @Test
    void cannotBuildUnconstrainedNetworkToolAfterARejectedSetter() {
        var builder = JavaToolSpec.builder("web_fetch", Request.class, Response.class);
        assertThatThrownBy(() -> builder.sideEffects(ToolSideEffect.NETWORK_ACCESS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("constrained hosts");
    }

    @Test
    void mixedSideEffectsKeepHostsAndDropPure() {
        JavaToolSpec<Request, Response> spec = JavaToolSpec.builder("web_fetch", Request.class, Response.class)
                .networkAccess("api.example.com")
                .sideEffects(ToolSideEffect.FILE_WRITE, ToolSideEffect.NETWORK_ACCESS)
                .build();

        assertThat(spec.networkHosts()).containsExactly("api.example.com");
        assertThat(spec.sideEffects())
                .containsExactlyInAnyOrder(ToolSideEffect.FILE_WRITE, ToolSideEffect.NETWORK_ACCESS);
        assertThat(spec.pure()).isFalse();
    }

    @Test
    void removingNetworkAccessSideEffectAlsoClearsHosts() {
        JavaToolSpec<Request, Response> spec = JavaToolSpec.builder("web_fetch", Request.class, Response.class)
                .networkAccess("api.example.com")
                .sideEffects(ToolSideEffect.FILE_WRITE)
                .build();

        assertThat(spec.networkHosts()).isEmpty();
        assertThat(spec.sideEffects()).containsExactly(ToolSideEffect.FILE_WRITE);
    }

    @Test
    void validatesNameRecordTypesAndTimeout() {
        assertThatThrownBy(() -> JavaToolSpec.builder(" ", Request.class, Response.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> JavaToolSpec.builder("weather_get", Request.class, Response.class)
                        .timeout(Duration.ZERO)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout must be positive");
    }
}
