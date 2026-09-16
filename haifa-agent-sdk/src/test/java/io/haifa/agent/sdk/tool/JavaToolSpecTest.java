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
