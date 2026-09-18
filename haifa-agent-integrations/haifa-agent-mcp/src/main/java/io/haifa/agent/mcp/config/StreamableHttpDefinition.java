package io.haifa.agent.mcp.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public record StreamableHttpDefinition(
        URI endpoint,
        boolean allowLoopbackHttp,
        Set<String> allowedOrigins,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration idleTimeout,
        int maxBodyBytes,
        int maxHeaderBytes)
        implements McpTransportDefinition {
    public StreamableHttpDefinition {
        Objects.requireNonNull(endpoint, "endpoint");
        allowedOrigins = Set.copyOf(Objects.requireNonNull(allowedOrigins, "allowedOrigins"));
        connectTimeout = positive(connectTimeout, "connectTimeout");
        requestTimeout = positive(requestTimeout, "requestTimeout");
        idleTimeout = positive(idleTimeout, "idleTimeout");
        if (endpoint.getUserInfo() != null || endpoint.getFragment() != null || endpoint.getHost() == null) {
            throw new IllegalArgumentException("MCP endpoint must be an absolute credential-free HTTP URI");
        }
        boolean loopback = "127.0.0.1".equals(endpoint.getHost()) || "localhost".equalsIgnoreCase(endpoint.getHost());
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                && !(allowLoopbackHttp && loopback && "http".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("MCP HTTP requires HTTPS except explicit loopback development");
        }
        if (maxBodyBytes < 1024 || maxBodyBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("maxBodyBytes is out of range");
        }
        if (maxHeaderBytes < 1024 || maxHeaderBytes > 256 * 1024) {
            throw new IllegalArgumentException("maxHeaderBytes is out of range");
        }
        String origin = origin(endpoint);
        if (!allowedOrigins.contains(origin)) {
            throw new IllegalArgumentException("MCP endpoint origin is not allowlisted");
        }
    }

    @Override
    public String identityReference() {
        return "http:" + endpoint.getScheme().toLowerCase() + "://"
                + endpoint.getHost().toLowerCase() + ":" + effectivePort(endpoint) + endpoint.getPath();
    }

    public static String origin(URI uri) {
        return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + ":" + effectivePort(uri);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
