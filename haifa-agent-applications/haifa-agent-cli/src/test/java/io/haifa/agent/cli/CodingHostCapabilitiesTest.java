package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.coding.host.internal.CodingHostCapabilities;
import io.haifa.coding.host.internal.CodingHostException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodingHostCapabilitiesTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void selectsOnlyExplicitlyOfferedAvailableExtensionsWithoutChangingLegacyBehavior() throws Exception {
        assertThat(CodingHostCapabilities.negotiate(json.readTree("{}"), Set.of("preview-v1")))
                .isEmpty();
        var offered = json.readTree("{\"optionalCapabilities\":[\"preview-v1\",\"future-v1\",\"preview-v1\"]}");
        assertThat(CodingHostCapabilities.negotiate(offered, Set.of("preview-v1")))
                .containsExactly("preview-v1");
        assertThat(CodingHostCapabilities.negotiate(offered, Set.of())).isEmpty();
    }

    @Test
    void rejectsMalformedOrUnboundedOffers() throws Exception {
        for (String value : java.util.List.of("null", "true", "{}", "[1]", "[\"BAD\"]")) {
            var request = json.readTree("{\"optionalCapabilities\":" + value + "}");
            assertThatThrownBy(() -> CodingHostCapabilities.negotiate(request, Set.of()))
                    .isInstanceOf(CodingHostException.class);
        }
        var request = json.createObjectNode();
        var values = request.putArray("optionalCapabilities");
        for (int i = 0; i < 33; i++) values.add("preview-v1");
        assertThatThrownBy(() -> CodingHostCapabilities.negotiate(request, Set.of()))
                .isInstanceOf(CodingHostException.class);
    }
}
