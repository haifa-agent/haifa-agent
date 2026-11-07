package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.*;

/** CLI-only compatibility adapter around the reusable Host owner. */
final class CodingHostServer implements AutoCloseable {
    private final io.haifa.coding.host.internal.CodingHostServer delegate;

    private CodingHostServer(io.haifa.coding.host.internal.CodingHostServer delegate) {
        this.delegate = delegate;
    }

    static CodingHostServer start(Path workspace, Optional<Path> config, String bind, int port) {
        return start(workspace, config, bind, port, false);
    }

    static CodingHostServer start(Path workspace, Optional<Path> config, String bind, int port, boolean presentation) {
        return new CodingHostServer(
                io.haifa.coding.host.internal.CodingHostServer.start(workspace, config, bind, port, presentation));
    }

    static CodingHostServer start(StandaloneCodingAgent agent, String bind, int port) {
        return new CodingHostServer(io.haifa.coding.host.internal.CodingHostServer.start(agent.unwrap(), bind, port));
    }

    static CodingHostServer start(StandaloneCodingAgent agent, String bind, int port, List<String> summary) {
        return start(agent, bind, port, summary, false);
    }

    static CodingHostServer start(
            StandaloneCodingAgent agent, String bind, int port, List<String> summary, boolean presentation) {
        return new CodingHostServer(io.haifa.coding.host.internal.CodingHostServer.start(
                agent.unwrap(), bind, port, summary, presentation));
    }

    static InetAddress loopback(String bind) {
        return io.haifa.coding.host.internal.CodingHostServer.loopback(bind);
    }

    static ObjectNode oversizedResponse(ObjectMapper json, ObjectNode response) {
        return io.haifa.coding.host.internal.CodingHostServer.oversizedResponse(json, response);
    }

    io.haifa.coding.api.CodingClient embeddedClient() {
        return delegate.embeddedClient();
    }

    int port() {
        return delegate.port();
    }

    java.util.List<String> configurationSummary() {
        return delegate.configurationSummary();
    }

    void awaitTermination() throws InterruptedException {
        delegate.awaitTermination();
    }

    public void close() {
        delegate.close();
    }
}
