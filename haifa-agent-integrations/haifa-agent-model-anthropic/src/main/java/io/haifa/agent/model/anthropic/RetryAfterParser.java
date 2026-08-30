package io.haifa.agent.model.anthropic;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Strict parser for the standard Retry-After delta-seconds or HTTP-date forms. */
final class RetryAfterParser {
    private static final int MAX_HEADER_CHARS = 128;

    private RetryAfterParser() {}

    static Optional<Duration> parse(HttpHeaders headers, Instant now) {
        String value = headers.firstValue("Retry-After").orElse("").trim();
        if (value.isEmpty() || value.length() > MAX_HEADER_CHARS) return Optional.empty();
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException ignored) {
            // Continue with the RFC 1123 date form.
        }
        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
            Duration delay = Duration.between(now, retryAt);
            return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
        } catch (DateTimeParseException | ArithmeticException ignored) {
            return Optional.empty();
        }
    }
}
