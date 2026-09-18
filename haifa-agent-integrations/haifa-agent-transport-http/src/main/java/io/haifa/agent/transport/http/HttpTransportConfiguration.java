package io.haifa.agent.transport.http;

import java.time.Duration;
import java.util.Objects;

public record HttpTransportConfiguration(
        String apiVersion,
        int maximumRequestBytes,
        int defaultEventPageSize,
        int maximumEventPageSize,
        int sseQueueCapacity,
        Duration heartbeatInterval) {
    public static final HttpTransportConfiguration DEFAULT =
            new HttpTransportConfiguration("1.0", 1_048_576, 100, 1_000, 64, Duration.ofSeconds(15));

    public HttpTransportConfiguration {
        apiVersion = Objects.requireNonNull(apiVersion, "apiVersion must not be null")
                .trim();
        heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval must not be null");
        if (apiVersion.isEmpty()
                || maximumRequestBytes < 1
                || defaultEventPageSize < 1
                || maximumEventPageSize < defaultEventPageSize
                || sseQueueCapacity < 1
                || heartbeatInterval.isNegative()
                || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("invalid HTTP transport configuration");
        }
    }
}
