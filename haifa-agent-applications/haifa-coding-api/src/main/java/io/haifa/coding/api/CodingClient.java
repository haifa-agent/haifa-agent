package io.haifa.coding.api;

import io.haifa.coding.api.CodingProtocol.*;

/** Product-only client. Close releases this client's observations, never its shared Host or accepted execution. */
public interface CodingClient extends AutoCloseable {
    ConnectionInfo connection();

    QueryResult query(Query query);

    CommandResult execute(CommandRequest request);

    default CommandResult execute(Command command) {
        return execute(new CommandRequest(command, new CommandOptions(false, false, java.util.List.of())));
    }

    CodingPresentationSubscription observe(String sessionId);

    CodingPreviewSubscription preview(String sessionId, String runId);

    default Snapshot open(String sessionId) {
        try (var observation = observe(sessionId)) {
            return observation.initial();
        }
    }

    HistoryPage history(String sessionId, int limit, Long beforeSequence);

    default TerminalContext terminalContext() {
        throw new CodingClientException("PRODUCT_CAPABILITY_UNSUPPORTED", false);
    }

    default TerminalSession terminalSession(String sessionId) {
        throw new CodingClientException("PRODUCT_CAPABILITY_UNSUPPORTED", false);
    }

    default ActivityPage activity(ActivityRequest request) {
        throw new CodingClientException("PRODUCT_CAPABILITY_UNSUPPORTED", false);
    }

    boolean renew();

    @Override
    void close();
}
