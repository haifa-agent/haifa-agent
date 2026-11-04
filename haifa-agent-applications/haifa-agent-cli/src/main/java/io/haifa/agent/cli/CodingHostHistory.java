package io.haifa.agent.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage;
import java.util.Map;

/** Wire-byte budget for history only; state reads never depend on transcript size. */
final class CodingHostHistory {
    static final int MAXIMUM_BYTES = 512 * 1024;
    private final ObjectMapper json = new ObjectMapper();
    private final int maximumBytes;

    CodingHostHistory() {
        this(MAXIMUM_BYTES);
    }

    CodingHostHistory(int maximumBytes) {
        if (maximumBytes < 256) throw new IllegalArgumentException("history byte budget must be at least 256");
        this.maximumBytes = maximumBytes;
    }

    Map<String, Object> page(CodingSessionHistoryPage source) {
        CodingSessionHistoryPage candidate = source;
        while (true) {
            Map<String, Object> wire = CodingHostWire.history(candidate);
            try {
                if (json.writeValueAsBytes(wire).length <= maximumBytes) return wire;
            } catch (JsonProcessingException failure) {
                throw CodingHostException.internal("History could not be encoded");
            }
            if (candidate.items().isEmpty()) {
                throw CodingHostException.conflict("PAYLOAD_TOO_LARGE", "History metadata exceeds the byte budget");
            }
            long first = candidate.items().getFirst().sequence();
            int remove = 0;
            // A failure summary shares its source message sequence; never split that continuation boundary.
            while (remove < candidate.items().size()
                    && candidate.items().get(remove).sequence() == first) remove++;
            if (remove == candidate.items().size()) {
                throw CodingHostException.conflict("PAYLOAD_TOO_LARGE", "One history entry exceeds the byte budget");
            }
            candidate = new CodingSessionHistoryPage(
                    source.sessionId(),
                    candidate.items().subList(remove, candidate.items().size()),
                    true);
        }
    }
}
