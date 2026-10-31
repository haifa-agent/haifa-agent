package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.CodingCompactionResult;
import io.haifa.agent.application.project.product.coding.CodingModelControls;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingModelPreferences;
import io.haifa.agent.application.project.product.coding.CodingModelState;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.model.api.ImageInputProfile;
import io.haifa.agent.model.api.ModelImageDetail;
import io.haifa.agent.model.api.ModelImageSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodingHostWireTest {

    @Test
    void compactionThroughKeepsTheActualCursorValue() {
        CodingCompactionResult result =
                new CodingCompactionResult(Optional.of("summary-ref"), 3L, 12, 456, new MessageCursor(42L));

        Map<String, Object> wire = CodingHostWire.compaction(result);

        assertThat(wire).containsEntry("summaryReference", "summary-ref");
        assertThat(wire).containsEntry("summaryVersion", 3L);
        assertThat(wire).containsEntry("sourceMessageCount", 12);
        assertThat(wire).containsEntry("estimatedTokens", 456);
        assertThat(wire.get("through")).isEqualTo(Map.of("value", 42L));
    }

    @Test
    void compactionThroughKeepsZeroWhenReturned() {
        CodingCompactionResult result =
                new CodingCompactionResult(Optional.empty(), 0L, 0, 0, MessageCursor.BEFORE_FIRST);

        Map<String, Object> wire = CodingHostWire.compaction(result);

        assertThat(wire.get("through")).isEqualTo(Map.of("value", 0L));
    }

    @Test
    void modelProjectsEveryImageInputProfileField() {
        ImageInputProfile profile = new ImageInputProfile(
                Set.of(ModelImageSource.URL),
                Set.of("image/webp", "image/png"),
                7,
                1024L,
                4096L,
                512,
                true,
                Set.of(ModelImageDetail.LOW, ModelImageDetail.AUTO));
        CodingModelOption model = new CodingModelOption(
                "image-model",
                "Image Model",
                "provider",
                "Provider",
                Set.of("TEXT_CHAT"),
                128_000,
                4_096,
                available(),
                "",
                CodingModelControls.defaults(),
                CodingModelPreferences.recommended(),
                Optional.of(profile));

        Map<String, Object> wire = CodingHostWire.model(model);

        assertThat(wire).containsEntry("imageInput", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> projected = (Map<String, Object>) wire.get("imageInputProfile");
        assertThat(projected).isNotNull();
        assertThat(projected.keySet())
                .containsExactlyInAnyOrder(
                        "allowedSources",
                        "supportedMediaTypes",
                        "maxImagesPerRequest",
                        "maxBytesPerItem",
                        "maxTotalBytes",
                        "maxUrlCharacters",
                        "detailSupported",
                        "allowedDetails");
        assertThat(projected.get("allowedSources")).isEqualTo(List.of("URL"));
        assertThat(projected.get("supportedMediaTypes")).isEqualTo(List.of("image/png", "image/webp"));
        assertThat(projected).containsEntry("maxImagesPerRequest", 7);
        assertThat(projected).containsEntry("maxBytesPerItem", 1024L);
        assertThat(projected).containsEntry("maxTotalBytes", 4096L);
        assertThat(projected).containsEntry("maxUrlCharacters", 512);
        assertThat(projected).containsEntry("detailSupported", true);
        assertThat(projected.get("allowedDetails")).isEqualTo(List.of("AUTO", "LOW"));
    }

    @Test
    void modelWithoutImageInputProjectsFalseAndNullProfile() {
        CodingModelOption model =
                new CodingModelOption("text-model", "Text Model", "provider", "Provider", Set.of("TEXT_CHAT"), 128_000);

        Map<String, Object> wire = CodingHostWire.model(model);

        assertThat(wire).containsEntry("imageInput", false);
        assertThat(wire).containsEntry("imageInputProfile", null);
    }

    private static CodingModelState available() {
        return new CodingModelState(
                CodingModelState.Connection.CONNECTED,
                CodingModelState.BindingAvailability.AVAILABLE,
                CodingModelState.RuntimeStatus.NORMAL,
                CodingModelState.RunScope.IDLE);
    }
}
