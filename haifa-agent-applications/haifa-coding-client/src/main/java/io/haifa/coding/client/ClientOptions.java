package io.haifa.coding.client;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Native loopback Client options; connecting never starts or takes ownership of a Host. */
public record ClientOptions(URI endpoint, Duration requestTimeout, String name, String version) {
    public ClientOptions(URI endpoint) {
        this(endpoint, Duration.ofSeconds(30), "haifa-coding-java", "1");
    }

    public ClientOptions {
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(requestTimeout);
        Objects.requireNonNull(name);
        Objects.requireNonNull(version);
        if (requestTimeout.compareTo(Duration.ofMillis(100)) < 0
                || requestTimeout.compareTo(Duration.ofMinutes(10)) > 0
                || name.isBlank()
                || version.isBlank()
                || name.length() > 128
                || version.length() > 64) throw new IllegalArgumentException("Invalid Client options");
    }
}
