package io.haifa.agent.model.anthropic;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Strict parser for the standard Retry-After delta-seconds or HTTP-date forms. */
final class RetryAfterParser {
    private RetryAfterParser() {}

    static Optional<Duration> parse(HttpHeaders headers, Instant now) {
        return io.haifa.agent.model.api.RetryAfterParser.parse(headers, now);
    }
}
