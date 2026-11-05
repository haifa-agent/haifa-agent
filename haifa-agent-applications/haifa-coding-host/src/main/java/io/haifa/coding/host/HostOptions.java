package io.haifa.coding.host;

import java.nio.file.Path;
import java.util.*;

/** Trusted owner inputs; configuration content and concrete engine/provider types are not part of this API. */
public record HostOptions(
        Path workspace,
        Path dataDirectory,
        Optional<Path> configuration,
        String bind,
        int port,
        String displayName,
        Optional<BrowserOptions> browser) {
    public HostOptions(Path workspace, Path dataDirectory) {
        this(workspace, dataDirectory, Optional.empty(), "127.0.0.1", 0, "Haifa Coding", Optional.empty());
    }

    public HostOptions {
        workspace = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
        dataDirectory = Objects.requireNonNull(dataDirectory).toAbsolutePath().normalize();
        configuration = Objects.requireNonNull(configuration)
                .map(p -> p.toAbsolutePath().normalize());
        bind = Objects.requireNonNull(bind);
        displayName = Objects.requireNonNull(displayName).strip();
        browser = Objects.requireNonNull(browser);
        if (port < 0
                || port > 65535
                || displayName.isBlank()
                || displayName.codePointCount(0, displayName.length()) > 256
                || displayName.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid Host options");
    }
}
