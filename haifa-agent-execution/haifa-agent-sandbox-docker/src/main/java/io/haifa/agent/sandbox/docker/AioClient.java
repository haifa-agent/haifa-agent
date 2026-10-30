package io.haifa.agent.sandbox.docker;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Fixed AIO v1.11.0 protocol subset used by one execution.
 *
 * <p>The client performs no retries: a lost HTTP response never causes a command replay. Callers
 * distinguish a completed command, a running/unknown command and a protocol failure.
 */
interface AioClient {
    boolean awaitReady(URI baseUri, Duration timeout);

    ExecResult exec(URI baseUri, ExecRequest request);

    void kill(URI baseUri, String sessionId, Duration timeout);

    void closeSession(URI baseUri, String sessionId, Duration timeout);

    final class AioProtocolException extends RuntimeException {
        private final String code;

        AioProtocolException(String code, String safeMessage) {
            super(safeMessage);
            this.code = code;
        }

        AioProtocolException(String code, String safeMessage, Throwable cause) {
            super(safeMessage, cause);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    record ExecRequest(
            String command,
            String execDirectory,
            Map<String, String> environment,
            Duration softTimeout,
            Duration hardTimeout,
            int maxOutputCharacters) {}

    record ExecResult(
            String sessionId,
            String commandId,
            String status,
            Integer exitCode,
            byte[] stdout,
            byte[] stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated) {}
}
