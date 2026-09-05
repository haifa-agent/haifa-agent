package io.haifa.agent.context.compression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimpleJsonParserTest {

    @Test
    void parsesSimpleObject() {
        String json = """
                {
                    "name": "Haifa Agent",
                    "version": 1,
                    "active": true,
                    "nested": {
                        "key": "value"
                    },
                    "items": ["a", "b", "c"]
                }
                """;
        Map<String, Object> map = SimpleJsonParser.parseObject(json);
        assertThat(map).containsEntry("name", "Haifa Agent");
        assertThat(map).containsEntry("version", 1L);
        assertThat(map).containsEntry("active", true);
        assertThat(map.get("nested")).isInstanceOf(Map.class);
        assertThat(map.get("items")).isEqualTo(List.of("a", "b", "c"));
    }

    @Test
    void parsesSemanticSummaryJsonRoundtrip() {
        SemanticSummaryItem item = new SemanticSummaryItem("g01", "Complete task", List.of("[M1]"), SemanticConfidence.OBSERVED);
        SemanticConversationSummaryV1 original = new SemanticConversationSummaryV1(
                "v1",
                "zh",
                List.of(item),
                List.of(),
                new SemanticProgress(List.of(item), List.of(), List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        String json = """
                {
                    "schemaVersion": "v1",
                    "language": "zh",
                    "goals": [
                        {
                            "stableItemId": "g01",
                            "text": "Complete task",
                            "sourceRefs": ["[M1]"],
                            "confidence": "OBSERVED"
                        }
                    ],
                    "constraints": [],
                    "progress": {
                        "completed": [
                            {
                                "stableItemId": "g01",
                                "text": "Complete task",
                                "sourceRefs": ["[M1]"],
                                "confidence": "OBSERVED"
                            }
                        ],
                        "active": [],
                        "blocked": []
                    },
                    "decisions": [],
                    "nextSteps": [],
                    "criticalContext": [],
                    "unresolvedQuestions": []
                }
                """;

        Map<String, Object> parsed = SimpleJsonParser.parseObject(json);
        SemanticConversationSummaryV1 reconstructed = SemanticConversationSummaryV1.fromMap(parsed);
        assertThat(reconstructed).isEqualTo(original);
        assertThat(reconstructed.toMap()).isNotNull();
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> SimpleJsonParser.parseObject("not json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SimpleJsonParser.parseObject("{\"unclosed\": "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
