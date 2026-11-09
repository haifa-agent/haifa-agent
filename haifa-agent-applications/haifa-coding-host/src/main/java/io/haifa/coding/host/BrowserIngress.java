package io.haifa.coding.host;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;

/** Owner-configured public HTTPS origin and proof shared only with the loopback reverse proxy. */
public final class BrowserIngress {
    private final URI externalOrigin;
    private final byte[] proxyProof;

    public BrowserIngress(URI externalOrigin, String proxyProof) {
        Objects.requireNonNull(externalOrigin);
        var host = externalOrigin.getHost();
        int port = externalOrigin.getPort();
        if (!"https".equals(externalOrigin.getScheme())
                || host == null
                || !host.equals(host.toLowerCase(Locale.ROOT))
                || host.endsWith(".")
                || port == 0
                || port == 443
                || port > 65535
                || !externalOrigin.toString().equals("https://" + host + (port == -1 ? "" : ":" + port))) {
            throw new IllegalArgumentException(
                    "Expected a canonical HTTPS origin without a path or explicit default port");
        }
        if (proxyProof == null || !proxyProof.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected a 32-byte lowercase hexadecimal proxy proof");
        }
        this.externalOrigin = externalOrigin;
        this.proxyProof = proxyProof.getBytes(StandardCharsets.US_ASCII);
    }

    public URI externalOrigin() {
        return externalOrigin;
    }

    public boolean matchesProxyProof(String candidate) {
        return candidate != null
                && candidate.length() == proxyProof.length
                && MessageDigest.isEqual(proxyProof, candidate.getBytes(StandardCharsets.UTF_8));
    }
}
