package io.haifa.coding.host.internal;

import io.haifa.coding.host.BrowserOptions;
import io.haifa.coding.host.PairingChallenge;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.function.*;

/** Process-local browser authorization. Admission/revocation serialize briefly; domain execution never holds the lock. */
public final class CodingBrowserAccess implements AutoCloseable {
    private final BrowserOptions options;
    private final String incarnation, origin;
    private final Clock clock;
    private final Supplier<String> secret, clientIds;
    private final Consumer<String> closeClient;
    private final Map<String, Pending> challenges = new HashMap<>();
    private final Map<String, Context> grants = new HashMap<>();
    private final Map<String, Context> clients = new HashMap<>();
    private boolean closed;
    private long attemptWindow;
    private int attempts;

    private record Pending(byte[] digest, long expiresAt, int remaining) {}

    public CodingBrowserAccess(
            BrowserOptions options,
            String incarnation,
            String origin,
            Supplier<String> clientIds,
            Consumer<String> closeClient) {
        this(options, incarnation, origin, clientIds, closeClient, Clock.systemUTC(), secureSecrets());
    }

    CodingBrowserAccess(
            BrowserOptions options,
            String incarnation,
            String origin,
            Supplier<String> clientIds,
            Consumer<String> closeClient,
            Clock clock,
            Supplier<String> secret) {
        this.options = options;
        this.incarnation = incarnation;
        this.origin = origin;
        this.clientIds = clientIds;
        this.closeClient = closeClient;
        this.clock = clock;
        this.secret = secret;
    }

    private static Supplier<String> secureSecrets() {
        var random = new SecureRandom();
        return () -> {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        };
    }

    public synchronized PairingChallenge challenge() {
        requireOpen();
        challenges.entrySet().removeIf(e -> clock.millis() >= e.getValue().expiresAt());
        if (challenges.size() >= options.maximumChallenges()) throw denied("PAIRING_LIMIT");
        String id = secret.get(), proof = secret.get();
        long expires = clock.millis() + options.challengeLifetime().toMillis();
        challenges.put(id, new Pending(digest(proof), expires, options.maximumAttempts()));
        return new PairingChallenge(id + "." + proof, incarnation, expires);
    }

    public io.haifa.coding.api.CodingProtocol.BrowserGrant exchange(
            String proof, String requestedIncarnation, String requestOrigin) {
        validateOrigin(requestOrigin, true);
        synchronized (this) {
            requireOpen();
            long now = clock.millis();
            if (now - attemptWindow >= 60000 || now < attemptWindow) {
                attemptWindow = now;
                attempts = 0;
            }
            if (++attempts > 64) throw denied("PAIRING_RATE_LIMIT");
            String[] parts = proof == null ? new String[0] : proof.split("\\.", -1);
            Pending pending = parts.length == 2 ? challenges.get(parts[0]) : null;
            if (pending == null) throw denied("PAIRING_REJECTED");
            if (now >= pending.expiresAt() || !incarnation.equals(requestedIncarnation)) {
                challenges.remove(parts[0]);
                throw denied("PAIRING_REJECTED");
            }
            if (!MessageDigest.isEqual(pending.digest(), digest(parts[1]))) {
                if (pending.remaining() <= 1) challenges.remove(parts[0]);
                else
                    challenges.put(
                            parts[0], new Pending(pending.digest(), pending.expiresAt(), pending.remaining() - 1));
                throw denied("PAIRING_REJECTED");
            }
            if (clients.size() >= 16) throw denied("BROWSER_CLIENT_LIMIT");
            challenges.remove(parts[0]);
            String client = "browser-" + clientIds.get(), token = incarnation + "." + secret.get();
            if (clients.containsKey(client) || grants.containsKey(key(token))) throw denied("BROWSER_ID_COLLISION");
            long expires = now + options.grantLifetime().toMillis();
            var context = new Context(
                    client, expires, now + options.clientIdleTimeout().toMillis());
            grants.put(key(token), context);
            clients.put(client, context);
            return new io.haifa.coding.api.CodingProtocol.BrowserGrant(token, client, incarnation, expires);
        }
    }
    /** The returned context proves this request's admission even if revocation follows immediately. */
    public Context admit(String token, String requestOrigin, String claimedClient) {
        validateOrigin(requestOrigin, false);
        Context context;
        synchronized (this) {
            requireOpen();
            context = token == null ? null : grants.get(key(token));
        }
        if (context == null || (claimedClient != null && !claimedClient.equals(context.clientId)))
            throw denied("BROWSER_AUTH_REQUIRED");
        context.requireValid();
        return context;
    }

    public void validateOrigin(String actual, boolean required) {
        if ((required && actual == null) || (actual != null && !origin.equals(actual)))
            throw denied("BROWSER_ORIGIN_REJECTED");
    }

    public synchronized boolean ownsClient(String id) {
        return clients.containsKey(id);
    }

    public void revoke(String clientId) {
        Context context;
        synchronized (this) {
            context = clients.get(clientId);
        }
        if (context != null) context.close();
    }

    public void expire() {
        List<Context> copy;
        synchronized (this) {
            copy = List.copyOf(clients.values());
            challenges.entrySet().removeIf(e -> clock.millis() >= e.getValue().expiresAt());
        }
        for (var c : copy) if (!c.valid()) c.close();
    }

    @Override
    public void close() {
        List<Context> copy;
        synchronized (this) {
            if (closed) return;
            closed = true;
            challenges.clear();
            copy = List.copyOf(clients.values());
        }
        copy.forEach(Context::close);
    }

    private void requireOpen() {
        if (closed) throw denied("BROWSER_AUTH_REQUIRED");
    }

    private static CodingHostException denied(String reason) {
        return CodingHostException.conflict(reason, "Browser authorization is unavailable");
    }

    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String key(String text) {
        return HexFormat.of().formatHex(digest(text));
    }

    public final class Context implements AutoCloseable {
        private final String clientId;
        private final long expiresAt;
        private long idleUntil;
        private final Map<String, AutoCloseable> observations = new HashMap<>();
        private Set<String> capabilities = Set.of();
        private boolean revoked;

        private Context(String clientId, long expiresAt, long idleUntil) {
            this.clientId = clientId;
            this.expiresAt = expiresAt;
            this.idleUntil = idleUntil;
        }

        public String clientId() {
            return clientId;
        }

        public synchronized boolean valid() {
            return !revoked && clock.millis() < expiresAt && clock.millis() < idleUntil;
        }

        public void requireValid() {
            if (!valid()) {
                close();
                throw denied("BROWSER_AUTH_EXPIRED");
            }
        }

        public synchronized Set<String> negotiate(Set<String> selected) {
            return negotiate(selected, ignored -> {});
        }

        /** Only short client-registry installation; never domain execution or network I/O. */
        public synchronized Set<String> negotiate(
                Set<String> selected, java.util.function.Consumer<Set<String>> install) {
            if (!valid()) throw denied("BROWSER_AUTH_EXPIRED");
            var previous = capabilities;
            install.accept(previous);
            capabilities = Set.copyOf(selected);
            return previous;
        }

        public synchronized void requireCapability(String capability) {
            if (!valid()) throw denied("BROWSER_AUTH_EXPIRED");
            if (!capabilities.contains(capability))
                throw CodingHostException.unsupported("Optional capability was not negotiated");
        }

        public synchronized void renew() {
            if (!valid()) throw denied("BROWSER_AUTH_EXPIRED");
            idleUntil = Math.min(
                    expiresAt, clock.millis() + options.clientIdleTimeout().toMillis());
        }

        public AutoCloseable own(String key, AutoCloseable observation) {
            synchronized (this) {
                if (valid() && observations.size() < 64 && !observations.containsKey(key)) {
                    observations.put(key, observation);
                    return () -> release(key, observation);
                }
            }
            closeObservation(observation);
            throw denied("BROWSER_OBSERVATION_UNAVAILABLE");
        }

        public void release(String key) {
            AutoCloseable resource;
            synchronized (this) {
                resource = observations.remove(key);
            }
            if (resource != null) closeObservation(resource);
        }

        private void release(String key, AutoCloseable observation) {
            synchronized (this) {
                observations.remove(key, observation);
            }
            closeObservation(observation);
        }

        @Override
        public void close() {
            List<AutoCloseable> owned;
            synchronized (this) {
                if (revoked) return;
                revoked = true;
                owned = List.copyOf(observations.values());
                observations.clear();
                capabilities = Set.of();
            }
            synchronized (CodingBrowserAccess.this) {
                clients.remove(clientId, this);
                grants.values().removeIf(value -> value == this);
            }
            owned.forEach(CodingBrowserAccess::closeObservation);
            closeClient.accept(clientId);
        }
    }

    private static void closeObservation(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            /* Cleanup cannot authorize or cancel execution. */
        }
    }
}
