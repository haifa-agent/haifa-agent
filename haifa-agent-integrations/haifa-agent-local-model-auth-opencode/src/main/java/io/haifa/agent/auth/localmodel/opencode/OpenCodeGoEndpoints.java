package io.haifa.agent.auth.localmodel.opencode;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Official OpenCode Console endpoints and the public CLI client identifier used by the device grant.
 *
 * <p>OpenCode Go is an OpenCode Console subscription. The console-owned provider is reached with the same
 * device authorization grant the open source CLI uses, so this driver only owns the console base, the public
 * client identifier, and the documented verification page. Nothing here is read from another product's local
 * credentials or keyring.
 */
public final class OpenCodeGoEndpoints {
    /** Public client identifier of the open source CLI; it is not a secret and is not environment-injected. */
    public static final String CLIENT_ID = "opencode-cli";

    /** RFC 8628 device grant type, verbatim from the console contract. */
    public static final String DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";

    /** Stable registration marker so a refreshed credential can be traced back to this driver. */
    public static final String CLIENT_REGISTRATION_REF = "opencode-go-console-v1";

    /**
     * Stable product user agent.
     *
     * <p>The console sits behind an edge that refuses unrecognised client signatures with an HTTP error page, so
     * every request identifies this product instead of the JDK default. It never claims to be a browser.
     */
    public static final String USER_AGENT = "haifa-coding-agent/opencode-go-login";

    private static final URI OFFICIAL_CONSOLE_BASE = URI.create("https://opencode.ai/console");

    private final String base;
    private final String verification;

    /** Official endpoints; production assemblies use this constructor. */
    public OpenCodeGoEndpoints() {
        this(OFFICIAL_CONSOLE_BASE);
    }

    /**
     * Overridable console base for loopback stubs only. The value must already be a clean absolute HTTP(S) base;
     * a plain-HTTP base is accepted only for loopback hosts.
     */
    public OpenCodeGoEndpoints(URI consoleBase) {
        String normalized = requireBase(consoleBase);
        this.base = normalized;
        this.verification = normalized + "/device";
    }

    public URI consoleBase() {
        return URI.create(base);
    }

    public URI deviceCodeUri() {
        return URI.create(base + "/auth/device/code");
    }

    public URI deviceTokenUri() {
        return URI.create(base + "/auth/device/token");
    }

    public URI tokenUri() {
        return URI.create(base + "/auth/token");
    }

    public URI userUri() {
        return URI.create(base + "/api/user");
    }

    /** Human verification page. It never carries the user code in the query string. */
    public URI verificationUri() {
        return URI.create(verification);
    }

    private static String requireBase(URI consoleBase) {
        URI value = Objects.requireNonNull(consoleBase, "consoleBase must not be null");
        if (!value.isAbsolute()
                || value.getHost() == null
                || value.getRawUserInfo() != null
                || value.getRawQuery() != null
                || value.getRawFragment() != null) {
            throw new IllegalArgumentException("console base must be a clean absolute network URI");
        }
        String scheme = value.getScheme().toLowerCase(Locale.ROOT);
        String host = value.getHost().toLowerCase(Locale.ROOT);
        boolean loopback =
                "http".equals(scheme) && Set.of("localhost", "127.0.0.1", "::1").contains(host);
        if (!"https".equals(scheme) && !loopback) {
            throw new IllegalArgumentException("console base must use HTTPS");
        }
        String path = value.getPath() == null ? "" : value.getPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) throw new IllegalArgumentException("console base must include the console path");
        return scheme + "://" + host + (value.getPort() < 0 ? "" : ":" + value.getPort()) + path;
    }
}
