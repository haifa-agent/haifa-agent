package io.haifa.agent.transport.http;

import java.util.function.Supplier;

/**
 * Host bridge that binds a trusted caller while invoking an embedded Runtime.
 * Implementations must clear thread-local or scoped identity in a finally block.
 */
@FunctionalInterface
public interface RuntimeCallerScope {
    <T> T call(TrustedCallerContext caller, Supplier<T> operation);
}
