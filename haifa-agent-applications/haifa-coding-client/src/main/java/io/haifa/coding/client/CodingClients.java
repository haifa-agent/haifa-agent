package io.haifa.coding.client;

import io.haifa.coding.api.CodingClient;
import java.net.URI;

/** Native remote product clients, independent of engine, CLI, UI and persistence. */
public final class CodingClients {
    private CodingClients() {}

    public static CodingClient connect(URI endpoint) {
        return connect(new ClientOptions(endpoint));
    }

    public static CodingClient connect(ClientOptions options) {
        return HttpCodingClient.connect(options);
    }
}
