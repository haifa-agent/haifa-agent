package io.haifa.agent.core.content;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** User-supplied remote image reference. Runtime adapters decide how it reaches the model. */
public record ImageUrlContentPart(URI url) implements ContentPart {
    public ImageUrlContentPart {
        url = Objects.requireNonNull(url, "url must not be null").normalize();
        if (!url.isAbsolute() || !"https".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalArgumentException("image URL must be absolute HTTPS");
        }
        if (url.getHost() == null
                || url.getHost().isBlank()
                || url.getUserInfo() != null
                || url.getFragment() != null) {
            throw new IllegalArgumentException("image URL must contain a safe host and no credentials or fragment");
        }
        if (privateLiteral(url.getHost())) {
            throw new IllegalArgumentException("image URL must not target a local or private host");
        }
        if (url.toASCIIString().length() > 2_048) throw new IllegalArgumentException("image URL is too long");
    }

    @Override
    public String contentType() {
        return "image-url";
    }

    @Override
    public String toString() {
        return "ImageUrlContentPart[host=" + url.getHost().toLowerCase(Locale.ROOT) + "]";
    }

    private static boolean privateLiteral(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.equals("localhost")
                || host.endsWith(".localhost")
                || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1")
                || host.startsWith("fe80:")
                || host.startsWith("fc")
                || host.startsWith("fd")) return true;
        String[] octets = host.split("\\.");
        if (octets.length != 4) return false;
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            return first == 0
                    || first == 10
                    || first == 127
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
