package io.haifa.agent.store.sqlite.payload;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.api.ApprovalPresentation;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InteractionTargetPayloadTest {

    @Test
    void versionOnePayloadCarriesOnlyTheTarget() {
        InteractionTargetPayload payload = InteractionTargetPayload.from(toolTarget());

        assertThat(payload.toDomain()).isEqualTo(toolTarget());
    }

    @Test
    void versionTwoPayloadRoundTripsStructuredApprovalPresentationThroughTheCodec() {
        ApprovalPresentation presentation = new ApprovalPresentation(
                "执行 PowerShell 命令",
                "为了观察终端工具调用的实际效果。",
                "PowerShell",
                "Start-Sleep -Seconds 4",
                List.of(new ApprovalPresentation.Fact("执行位置", "本机环境")),
                List.of(new ApprovalPresentation.Fact("调用摘要", "digest-123")),
                Optional.of("HIGH"));
        InteractionTargetPayloadV2 payload = InteractionTargetPayloadV2.from(toolTarget(), Optional.of(presentation));

        assertThat(payload.toDomain()).isEqualTo(toolTarget());
        assertThat(payload.presentationDomain()).contains(presentation);

        var codecs = SqliteRuntimePayloadTypes.create(1_000_000);
        var encoded = codecs.encode(SqliteRuntimePayloadTypes.INTERACTION_TARGET_V2, payload);
        InteractionTargetPayloadV2 decoded = codecs.decode(SqliteRuntimePayloadTypes.INTERACTION_TARGET_V2, encoded);

        assertThat(decoded.toDomain()).isEqualTo(toolTarget());
        assertThat(decoded.presentationDomain()).contains(presentation);
    }

    @Test
    void versionTwoPayloadDecodesAbsentPresentationAsEmpty() {
        InteractionTargetPayloadV2 payload = InteractionTargetPayloadV2.from(toolTarget(), Optional.empty());

        assertThat(payload.presentationDomain()).isEmpty();
    }

    private static ToolApprovalTarget toolTarget() {
        return new ToolApprovalTarget(
                new ToolCallId("call-1"),
                "execution_run",
                "definition-hash",
                "arguments-digest",
                "local:user:test",
                "requirement-digest");
    }
}
