package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryItem;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.coding.host.internal.CodingHostHistory;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CodingHostHistoryTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void cjkHistoryFitsTheWireBudgetAndContinuesBeforeTheRetainedHead() throws Exception {
        var items = IntStream.rangeClosed(1, 50)
                .mapToObj(i -> item(i, "中".repeat(8000)))
                .toList();
        var wire = new CodingHostHistory()
                .page(new CodingSessionHistoryPage(new AgentSessionId("session-1"), items, false));
        var encoded = json.writeValueAsBytes(wire);
        var result = json.readTree(encoded);
        assertThat(encoded.length).isLessThanOrEqualTo(CodingHostHistory.MAXIMUM_BYTES);
        assertThat(result.path("items").size()).isBetween(1, 49);
        assertThat(result.path("items")
                        .get(result.path("items").size() - 1)
                        .path("sequence")
                        .asLong())
                .isEqualTo(50);
        assertThat(result.path("earlierHistoryAvailable").asBoolean()).isTrue();
        assertThat(result.path("nextBeforeSequence").asLong())
                .isEqualTo(result.path("items").get(0).path("sequence").asLong());
        assertThat(result.path("items").get(0).path("body").asText()).isEqualTo("中".repeat(8000));
    }

    @Test
    void countsJsonEscapingAndKeepsSharedSequenceEntriesTogether() throws Exception {
        String body = "quote\"\\\n".repeat(30);
        var items = List.of(item(1, body), item(1, body), item(2, body));
        var wire = new CodingHostHistory(600)
                .page(new CodingSessionHistoryPage(new AgentSessionId("session-1"), items, false));
        byte[] encoded = json.writeValueAsBytes(wire);
        assertThat(encoded.length).isLessThanOrEqualTo(600);
        assertThat(json.readTree(encoded).path("items").size()).isEqualTo(1);
        assertThat(wire.get("nextBeforeSequence")).isEqualTo(2L);
    }

    @Test
    void overflowErrorKeepsItsRequestIdentity() throws Exception {
        var original = json.createObjectNode().put("jsonrpc", "2.0").put("id", "request-17");
        original.put("result", "synthetic oversized result");
        var error = CodingHostServer.oversizedResponse(json, original);
        assertThat(error.path("id").asText()).isEqualTo("request-17");
        assertThat(error.path("error").path("data").path("reasonCode").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(json.writeValueAsBytes(error).length).isLessThan(1024);
    }

    private static CodingSessionHistoryItem item(long sequence, String body) {
        return new CodingSessionHistoryItem(
                "item-" + sequence,
                CodingSessionHistoryItem.Kind.USER,
                "You",
                body,
                "COMPLETED",
                sequence,
                Instant.EPOCH);
    }
}
