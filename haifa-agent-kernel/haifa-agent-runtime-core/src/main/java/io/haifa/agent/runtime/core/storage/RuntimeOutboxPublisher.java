package io.haifa.agent.runtime.core.storage;

import java.util.List;

public interface RuntimeOutboxPublisher {
    void append(OutboxMessage message);

    List<OutboxMessage> pending();

    void markPublished(String eventId);

    boolean markConsumed(String consumerId, String eventId);
}
