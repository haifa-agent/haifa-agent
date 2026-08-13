package io.haifa.agent.personalassistant.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ResearchTimeRangeFreezerTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-12T02:15:00Z");

    @Test
    void freezesSupportedRelativeRangesAgainstTheUtcCreationDate() {
        assertThat(ResearchTimeRangeFreezer.freezeRange("过去3年至今", CREATED_AT))
                .isEqualTo("2023-08-12 至 2026-08-12（UTC，创建时冻结）");
        assertThat(ResearchTimeRangeFreezer.freezeRange("近6个月", CREATED_AT))
                .isEqualTo("2026-02-12 至 2026-08-12（UTC，创建时冻结）");
        assertThat(ResearchTimeRangeFreezer.freezeRange("过去一年", CREATED_AT))
                .isEqualTo("2025-08-12 至 2026-08-12（UTC，创建时冻结）");
    }

    @Test
    void preservesAlreadyExplicitOrUnsupportedRanges() {
        assertThat(ResearchTimeRangeFreezer.freezeRange("2024-01-01 至 2026-08-12", CREATED_AT))
                .isEqualTo("2024-01-01 至 2026-08-12");
        assertThat(ResearchTimeRangeFreezer.freezeRange("未来三年", CREATED_AT)).isEqualTo("未来三年");
    }
}
