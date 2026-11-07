package io.haifa.coding.host;

import io.haifa.coding.api.CodingClient;
import io.haifa.coding.host.internal.CodingHostServer;
import java.net.URI;
import java.util.Optional;

/** Explicit owner of one Host/Runtime/store assembly. Client/observation handles have independent lifetimes. */
public final class CodingHost implements AutoCloseable {
    private final CodingHostServer server;

    CodingHost(CodingHostServer server) {
        this.server = server;
    }

    public URI endpoint() {
        return server.endpoint();
    }

    public Optional<URI> browserUri() {
        return server.browserUri();
    }

    public CodingClient embeddedClient() {
        return server.embeddedClient();
    }

    public PairingChallenge newPairingChallenge() {
        return server.newPairingChallenge();
    }

    public void revokeBrowserClient(String clientId) {
        server.revokeBrowserClient(clientId);
    }

    @Override
    public void close() {
        server.close();
    }
}
