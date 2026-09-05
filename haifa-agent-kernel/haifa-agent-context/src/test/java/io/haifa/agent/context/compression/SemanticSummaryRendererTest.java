package io.haifa.agent.context.compression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticSummaryRendererTest {

    @Test
    void rendersStructuredMarkdownDeterministically() {
        SemanticConversationSummaryV1 summary = new SemanticConversationSummaryV1(
                "v1",
                "zh-CN",
                List.of(new SemanticSummaryItem("g01", "Compile report", List.of("m001"), SemanticConfidence.OBSERVED)),
                List.of(new SemanticSummaryItem("c01", "ENV-ACCEPT-V3 template", List.of("m001"), SemanticConfidence.OBSERVED)),
                new SemanticProgress(
                        List.of(new SemanticSummaryItem("p01", "3-day monitoring complete", List.of("m002"), SemanticConfidence.OBSERVED)),
                        List.of(new SemanticSummaryItem("p02", "Archive energy report", List.of("m003"), SemanticConfidence.OBSERVED)),
                        List.of(new SemanticSummaryItem("p03", "Noise issue rectification", List.of("m004"), SemanticConfidence.OBSERVED))),
                List.of(new SemanticDecisionItem("d01", "Acceptance passed", "Re-test compliant", SemanticDecisionStatus.ACCEPTED, List.of("m005"))),
                List.of(new SemanticSummaryItem("n01", "Archive by Sept 30", List.of("m003"), SemanticConfidence.OBSERVED)),
                List.of(new SemanticSummaryItem("ctx01", "Reports: QHJC-2026-0615", List.of("m002"), SemanticConfidence.OBSERVED)),
                List.of(new SemanticSummaryItem("q01", "Confirm archive recipient", List.of("m003"), SemanticConfidence.OBSERVED)));

        String markdown = SemanticSummaryRenderer.renderMarkdown(summary);

        assertThat(markdown).contains("Historical derived conversation summary; authoritative domain state and recent raw turns take precedence.");
        assertThat(markdown).contains("## Goals\n- Compile report [m001]");
        assertThat(markdown).contains("## Constraints and Preferences\n- ENV-ACCEPT-V3 template [m001]");
        assertThat(markdown).contains("## Progress\n### Completed\n- 3-day monitoring complete [m002]\n### Active\n- Archive energy report [m003]\n### Blocked\n- Noise issue rectification [m004]");
        assertThat(markdown).contains("## Decisions\n- [ACCEPTED] Acceptance passed — Re-test compliant [m005]");
        assertThat(markdown).contains("## Critical Context\n- Reports: QHJC-2026-0615 [m002]");
        assertThat(markdown).contains("## Next Steps\n- Archive by Sept 30 [m003]");
        assertThat(markdown).contains("## Unresolved Questions\n- Confirm archive recipient [m003]");
    }
}
