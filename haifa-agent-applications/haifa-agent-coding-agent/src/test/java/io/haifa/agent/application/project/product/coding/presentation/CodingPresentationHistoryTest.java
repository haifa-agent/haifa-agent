package io.haifa.agent.application.project.product.coding.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryItem;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.credential.core.DefaultSecretRedactor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class CodingPresentationHistoryTest {
    @Test
    void preservesWholeMessageGroupsWhenFailureNoticesExceedThePageEntryBudget() {
        var items = new ArrayList<CodingSessionHistoryItem>();
        for (int sequence = 1; sequence <= 100; sequence++) {
            items.add(item(sequence, CodingSessionHistoryItem.Kind.USER, "input"));
            items.add(item(sequence, CodingSessionHistoryItem.Kind.ERROR, "safe failure"));
        }
        var page = CodingPresentationHistory.page(
                new CodingSessionHistoryPage(new AgentSessionId("session"), items, false, OptionalLong.empty()),
                new DefaultSecretRedactor());
        assertThat(page.entries()).hasSize(100);
        assertThat(page.entries().getFirst().sequence()).isEqualTo(51);
        assertThat(page.entries().getLast().sequence()).isEqualTo(100);
        assertThat(page.history().beforeSequence()).isEqualTo(51);
        assertThat(page.history().hasEarlier()).isTrue();
        assertThat(page.history().complete()).isFalse();
    }

    @Test
    void boundsCjkPagesBySerializedUtf8BytesAndRetainsAUsableCursor() throws Exception {
        var items = new ArrayList<CodingSessionHistoryItem>();
        for (int sequence = 1; sequence <= 32; sequence++)
            items.add(item(sequence, CodingSessionHistoryItem.Kind.USER, "汉".repeat(8000)));
        var page = CodingPresentationHistory.page(
                new CodingSessionHistoryPage(new AgentSessionId("session"), items, false, OptionalLong.empty()),
                new DefaultSecretRedactor());
        assertThat(new ObjectMapper().writeValueAsBytes(page).length).isLessThanOrEqualTo(262144);
        assertThat(page.entries()).isNotEmpty().hasSizeLessThan(32);
        assertThat(page.history().beforeSequence())
                .isEqualTo(page.entries().getFirst().sequence());
        assertThat(page.history().complete()).isFalse();
    }

    private static CodingSessionHistoryItem item(long sequence, CodingSessionHistoryItem.Kind kind, String text) {
        return new CodingSessionHistoryItem(
                kind.name() + sequence,
                kind,
                "Title",
                text,
                "COMPLETED",
                sequence,
                Instant.EPOCH,
                Optional.of("run-" + sequence),
                false);
    }
}
