package io.haifa.agent.testing.transport;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Framework-neutral driver used by transport implementations to run the same protocol contract.
 * It intentionally exposes no Spring, socket, Runtime Core or Store types.
 */
public interface TransportTestDriver extends AutoCloseable {
    Response exchange(Request request);

    Stream openStream(Request request);

    record Request(
            String method,
            String path,
            Map<String, List<String>> headers,
            Map<String, List<String>> query,
            byte[] body) {}

    record Response(int status, Map<String, String> headers, byte[] body) {}

    interface Stream extends AutoCloseable {
        Frame next(Duration maximumWait) throws InterruptedException;

        boolean closed();
    }

    record Frame(Optional<String> id, Optional<String> event, Optional<String> data, Optional<String> comment) {}
}
