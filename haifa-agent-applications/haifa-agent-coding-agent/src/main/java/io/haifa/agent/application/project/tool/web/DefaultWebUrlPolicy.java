package io.haifa.agent.application.project.tool.web;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DefaultWebUrlPolicy implements WebUrlPolicy {
    private static final int MAX_URL_LENGTH = 4096;
    private static final Set<String> DENIED_HOSTS =
            Set.of("localhost", "metadata.google.internal", "metadata.azure.internal");
    private final Set<String> deniedDomains;

    public DefaultWebUrlPolicy() {
        this(Set.of());
    }

    public DefaultWebUrlPolicy(Set<String> deniedDomains) {
        this.deniedDomains = Set.copyOf(Objects.requireNonNull(deniedDomains, "deniedDomains")).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public String policyId() {
        return "default-public-web-url";
    }

    @Override
    public String policyVersion() {
        return "1.0.0";
    }

    @Override
    public Map<String, String> configuration() {
        return Map.of(
                "allowedPorts",
                "80,443",
                "allowedSchemes",
                "http,https",
                "deniedDomains",
                deniedDomains.stream().sorted().collect(java.util.stream.Collectors.joining(",")),
                "maxUrlLength",
                Integer.toString(MAX_URL_LENGTH));
    }

    @Override
    public WebUrlDecision evaluate(URI input) {
        Objects.requireNonNull(input, "url");
        URI normalized = input.normalize();
        if (normalized.toString().length() > MAX_URL_LENGTH) return WebUrlDecision.deny(normalized, "URL_TOO_LONG");
        String scheme = normalized.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return WebUrlDecision.deny(normalized, "SCHEME_DENIED");
        }
        String authority = normalized.getRawAuthority();
        if (normalized.getRawUserInfo() != null || (authority != null && authority.contains("@"))) {
            return WebUrlDecision.deny(normalized, "USER_INFO_DENIED");
        }
        String host = normalized.getHost();
        if (host == null) host = authorityHost(authority);
        if (host == null || host.isBlank()) return WebUrlDecision.deny(normalized, "HOST_REQUIRED");
        String hostValue = stripIpv6Brackets(host);
        String asciiHost;
        if (looksLikeLiteralAddress(hostValue)) {
            asciiHost = canonicalLiteral(hostValue);
            if (asciiHost == null) return WebUrlDecision.deny(normalized, "HOST_INVALID");
        } else {
            try {
                asciiHost = IDN.toASCII(hostValue, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException exception) {
                return WebUrlDecision.deny(normalized, "HOST_INVALID");
            }
        }
        if (asciiHost.length() > 253
                || DENIED_HOSTS.contains(asciiHost)
                || asciiHost.endsWith(".localhost")
                || asciiHost.endsWith(".local")
                || deniedDomains.stream()
                        .anyMatch(domain -> asciiHost.equals(domain) || asciiHost.endsWith("." + domain))) {
            return WebUrlDecision.deny(normalized, "HOST_DENIED");
        }
        int port = normalized.getPort();
        if (port == -1) port = authorityPort(authority);
        if (port != -1 && port != 80 && port != 443) {
            return WebUrlDecision.deny(normalized, "PORT_DENIED");
        }
        if (looksLikeLiteralAddress(asciiHost) && deniedLiteral(asciiHost)) {
            return WebUrlDecision.deny(normalized, "ADDRESS_DENIED");
        }
        try {
            URI safe = new URI(
                    scheme.toLowerCase(Locale.ROOT),
                    null,
                    asciiHost,
                    port,
                    normalized.getRawPath(),
                    normalized.getRawQuery(),
                    null);
            return WebUrlDecision.allow(safe);
        } catch (URISyntaxException exception) {
            return WebUrlDecision.deny(normalized, "URL_INVALID");
        }
    }

    private static boolean looksLikeLiteralAddress(String host) {
        return host.contains(":")
                || host.matches("[0-9.]+")
                || host.matches("(?i)0x[0-9a-f]+")
                || host.matches("[0-9]+");
    }

    private static boolean deniedLiteral(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return true;
            }
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) return deniedIpv4(bytes);
            return deniedIpv6(bytes);
        } catch (UnknownHostException exception) {
            return true;
        }
    }

    private static String canonicalLiteral(String host) {
        if (host.contains("%")) return null;
        try {
            return InetAddress.getByName(host).getHostAddress().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private static boolean deniedIpv4(byte[] value) {
        int first = Byte.toUnsignedInt(value[0]);
        int second = Byte.toUnsignedInt(value[1]);
        if (first == 0
                || first == 10
                || first == 127
                || first >= 224
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 198 && (second == 18 || second == 19))) {
            return true;
        }
        return (first == 192 && second == 0 && Byte.toUnsignedInt(value[2]) == 0)
                || (first == 192 && second == 0 && Byte.toUnsignedInt(value[2]) == 2)
                || (first == 198 && second == 51 && Byte.toUnsignedInt(value[2]) == 100)
                || (first == 203 && second == 0 && Byte.toUnsignedInt(value[2]) == 113);
    }

    private static boolean deniedIpv6(byte[] value) {
        int first = Byte.toUnsignedInt(value[0]);
        int second = Byte.toUnsignedInt(value[1]);
        boolean uniqueLocal = (first & 0xfe) == 0xfc;
        boolean documentation = first == 0x20
                && second == 0x01
                && Byte.toUnsignedInt(value[2]) == 0x0d
                && Byte.toUnsignedInt(value[3]) == 0xb8;
        return uniqueLocal || documentation;
    }

    private static String stripIpv6Brackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static String authorityHost(String authority) {
        if (authority == null || authority.isBlank()) return null;
        if (authority.startsWith("[")) {
            int closingBracket = authority.indexOf(']');
            return closingBracket > 1 ? authority.substring(1, closingBracket) : null;
        }
        int colon = authority.lastIndexOf(':');
        if (colon > 0 && authority.indexOf(':') == colon && digits(authority.substring(colon + 1))) {
            return authority.substring(0, colon);
        }
        return authority;
    }

    private static int authorityPort(String authority) {
        if (authority == null || authority.isBlank()) return -1;
        int colon = authority.startsWith("[") ? authority.indexOf(']') + 1 : authority.lastIndexOf(':');
        if (colon <= 0 || colon >= authority.length() - 1 || authority.charAt(colon) != ':') return -1;
        String value = authority.substring(colon + 1);
        if (!digits(value)) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -2;
        }
    }

    private static boolean digits(String value) {
        return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }
}
