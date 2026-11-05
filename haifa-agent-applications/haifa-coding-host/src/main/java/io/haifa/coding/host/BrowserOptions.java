package io.haifa.coding.host;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Explicit trusted assets and bounded, independent browser authorization lifetimes. */
public record BrowserOptions(
        Path assetRoot,
        String entryPoint,
        Duration challengeLifetime,
        Duration grantLifetime,
        Duration clientIdleTimeout,
        int maximumChallenges,
        int maximumAttempts) {
    public BrowserOptions(Path assetRoot, String entryPoint) {
        this(assetRoot, entryPoint, Duration.ofMinutes(2), Duration.ofMinutes(15), Duration.ofSeconds(90), 4, 5);
    }

    public BrowserOptions {
        assetRoot = Objects.requireNonNull(assetRoot).toAbsolutePath().normalize();
        entryPoint = Objects.requireNonNull(entryPoint);
        if (entryPoint.isBlank()
                || entryPoint.startsWith("/")
                || entryPoint.contains("..")
                || entryPoint.contains("\\")
                || entryPoint.contains("%")) throw new IllegalArgumentException("Invalid trusted entry point");
        limit(challengeLifetime, Duration.ofMinutes(10));
        limit(grantLifetime, Duration.ofHours(24));
        limit(clientIdleTimeout, Duration.ofMinutes(30));
        if (maximumChallenges < 1 || maximumChallenges > 16 || maximumAttempts < 1 || maximumAttempts > 8)
            throw new IllegalArgumentException("Invalid pairing limits");
    }

    private static void limit(Duration value, Duration maximum) {
        Objects.requireNonNull(value);
        if (value.compareTo(Duration.ofMillis(100)) < 0 || value.compareTo(maximum) > 0)
            throw new IllegalArgumentException("Invalid browser lifetime");
    }
}
