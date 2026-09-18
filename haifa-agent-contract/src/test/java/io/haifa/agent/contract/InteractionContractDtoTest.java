package io.haifa.agent.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.contract.common.ApiVersion;
import io.haifa.agent.contract.common.IdempotencyKey;
import io.haifa.agent.contract.common.TextContentPartDto;
import io.haifa.agent.contract.error.ErrorCode;
import io.haifa.agent.contract.error.ErrorResponse;
import io.haifa.agent.contract.run.ResumeRunRequest;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class InteractionContractDtoTest {
    @Test
    void copiesCollectionsAndRejectsUntrustedIdentityFieldsByConstruction() {
        List<io.haifa.agent.contract.common.ContentPartDto> mutable =
                new java.util.ArrayList<>(List.of(new TextContentPartDto("steer", "text/plain")));
        var request = new io.haifa.agent.contract.run.RunInputRequest(
                "input-1",
                "run-1",
                OptionalLong.of(2),
                mutable,
                new IdempotencyKey("input-key"),
                Instant.parse("2026-07-26T00:00:00Z"));
        mutable.clear();

        assertThat(request.contents()).hasSize(1);
        assertThat(io.haifa.agent.contract.run.RunInputRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("tenant", "principal", "authority");
    }

    @Test
    void resumeRequiresAnExplicitIdempotencyKeyAndNonNegativeVersion() {
        assertThat(new ResumeRunRequest("run-1", OptionalLong.empty(), new IdempotencyKey("resume-key")).runId())
                .isEqualTo("run-1");
        assertThatThrownBy(() -> new ResumeRunRequest("run-1", OptionalLong.of(-1), new IdempotencyKey("resume-key")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorEnvelopeUsesStableCodesWithoutInternalExceptionPayloads() {
        ErrorResponse response = new ErrorResponse(
                ApiVersion.CURRENT,
                ErrorCode.INTERACTION_NOT_FOUND,
                "Interaction was not found",
                "correlation-1",
                Instant.parse("2026-07-26T00:00:00Z"),
                List.of());

        assertThat(response.code()).isEqualTo(ErrorCode.INTERACTION_NOT_FOUND);
        assertThat(ErrorResponse.class.getRecordComponents())
                .extracting(component -> component.getType().getName())
                .noneMatch(type -> type.contains("Throwable") || type.contains("Exception"));
    }
}
