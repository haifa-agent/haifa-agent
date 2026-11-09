package io.haifa.agent.application.coding.terminal.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Product version displayed by the terminal surface.
 *
 * <p>The packaged value comes from {@code META-INF/haifa/coding-agent-version.properties}, whose
 * {@code version} key is filtered from the POM at build time. A local refresh package rewrites only that
 * key inside the shaded CLI JAR, so a worktree build can identify itself without touching Java code.
 */
public final class CodingAgentVersion {
    static final String RESOURCE = "META-INF/haifa/coding-agent-version.properties";
    static final String KEY = "version";

    private static final String UNKNOWN = "unknown";
    private static final int MAXIMUM_LENGTH = 64;
    private static final String VALUE = load();

    private CodingAgentVersion() {}

    public static String current() {
        return VALUE;
    }

    static String load() {
        String packaged = read(Thread.currentThread().getContextClassLoader());
        if (packaged == null) packaged = read(CodingAgentVersion.class.getClassLoader());
        if (packaged != null) return packaged;
        Package owner = CodingAgentVersion.class.getPackage();
        String manifest = owner == null ? null : owner.getImplementationVersion();
        String normalized = normalize(manifest);
        return normalized == null ? UNKNOWN : normalized;
    }

    static String read(ClassLoader loader) {
        if (loader == null) return null;
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) return null;
            Properties properties = new Properties();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return normalize(properties.getProperty(KEY));
        } catch (IOException | IllegalArgumentException exception) {
            return null;
        }
    }

    static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAXIMUM_LENGTH) return null;
        return trimmed.matches("[A-Za-z0-9._+-]+") ? trimmed : null;
    }
}
