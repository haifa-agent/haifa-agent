package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetryAfterParserTest {
    @Test
    void parsesDeltaSecondsAndHttpDateWithoutTrustingInvalidValues() {
        Instant now = Instant.parse("2026-08-21T00:00:00Z");

        assertThat(RetryAfterParser.parse(headers("5"), now)).contains(Duration.ofSeconds(5));
        String date =
                DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(7).atZone(ZoneOffset.UTC));
        assertThat(RetryAfterParser.parse(headers(date), now)).contains(Duration.ofSeconds(7));
        assertThat(RetryAfterParser.parse(headers("-1"), now)).isEmpty();
        assertThat(RetryAfterParser.parse(headers("not-a-delay"), now)).isEmpty();
        assertThat(RetryAfterParser.parse(headers("9".repeat(129)), now)).isEmpty();
    }

    private static HttpHeaders headers(String value) {
        return HttpHeaders.of(Map.of("Retry-After", List.of(value)), (name, ignored) -> true);
    }
}
