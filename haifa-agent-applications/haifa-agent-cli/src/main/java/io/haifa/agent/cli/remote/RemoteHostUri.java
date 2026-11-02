package io.haifa.agent.cli.remote;

import io.haifa.agent.application.project.product.ProjectProductException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

/** Validates that a remote Coding Host endpoint is a plain loopback HTTP base URI. */
final class RemoteHostUri {
    private static final String LOCALHOST = "localhost";

    private RemoteHostUri() {}

    static URI requireLoopbackBase(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (!"http".equalsIgnoreCase(endpoint.getScheme())) {
            throw invalid("Only the http scheme is accepted for a loopback Coding Host");
        }
        if (endpoint.getUserInfo() != null) {
            throw invalid("User information is not accepted in a Coding Host URI");
        }
        if (endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw invalid("Query and fragment components are not accepted in a Coding Host URI");
        }
        String path = endpoint.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw invalid("A Coding Host URI must not contain a path");
        }
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            throw invalid("A Coding Host URI requires a loopback host");
        }
        int port = endpoint.getPort();
        if (port < 1 || port > 65_535) {
            throw invalid("A Coding Host URI requires an explicit loopback port");
        }
        String normalizedHost = stripBrackets(host.toLowerCase(Locale.ROOT));
        if (!isLoopbackLiteral(normalizedHost)) {
            throw invalid("A Coding Host must use a loopback address");
        }
        try {
            InetAddress address = InetAddress.getByName(normalizedHost);
            if (!address.isLoopbackAddress()) {
                throw invalid("A Coding Host must use a loopback address");
            }
            return new URI("http", null, normalizedHost, port, "", null, null);
        } catch (UnknownHostException exception) {
            throw invalid("A Coding Host loopback address could not be resolved");
        } catch (URISyntaxException exception) {
            throw invalid("A Coding Host URI is invalid");
        }
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static boolean isLoopbackLiteral(String host) {
        if (host.equals(LOCALHOST)) {
            return true;
        }
        if (host.startsWith("127.") && host.matches("127(\\.[0-9]{1,3}){3}")) {
            return true;
        }
        return host.equals("::1") || host.equals("0:0:0:0:0:0:0:1");
    }

    private static ProjectProductException invalid(String message) {
        return new ProjectProductException("REMOTE_URI_REJECTED", message);
    }
}
