package io.haifa.agent.runtime.core.storage;

/** Registration boundary for post-persistence message redaction notifications. */
public interface MessageRedactionListenerRegistry {

    void register(MessageRedactionListener listener);
}
