package io.haifa.agent.personalassistant.server.configuration.security;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** MVP local-server boundary: loopback Host/Origin, bounded writes, and explicit CSRF header. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class LoopbackRequestFilter implements WebFilter {
    private static final long MAX_BODY_BYTES = 64 * 1024;
    private static final Set<String> MUTATIONS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        var response = exchange.getResponse();
        response.getHeaders().set("X-Content-Type-Options", "nosniff");
        response.getHeaders().set("X-Frame-Options", "DENY");
        response.getHeaders().set("Referrer-Policy", "no-referrer");
        response.getHeaders().set("Cache-Control", "no-store");
        long contentLength = request.getHeaders().getContentLength();
        String host = request.getHeaders().getHost() == null
                ? request.getURI().getHost()
                : request.getHeaders().getHost().getHostString();
        if (!loopbackHost(host) || !originAllowed(request.getHeaders().getOrigin()) || contentLength > MAX_BODY_BYTES) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }
        if (MUTATIONS.contains(request.getMethod().name())
                && request.getPath().value().startsWith("/api/")
                && !"1".equals(request.getHeaders().getFirst("X-Haifa-CSRF"))) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }
        return chain.filter(exchange);
    }

    private static boolean originAllowed(String origin) {
        if (origin == null || origin.isBlank()) return true;
        try {
            return loopbackHost(URI.create(origin).getHost());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean loopbackHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        return normalized.equals("localhost")
                || normalized.equals("127.0.0.1")
                || normalized.equals("::1")
                || normalized.equals("0:0:0:0:0:0:0:1");
    }
}
