package io.haifa.agent.runtime.core.loop;

/** Internal control signal used when a bounded active-context page cannot represent the complete session window. */
final class ActiveContextCompactionRequiredException extends RuntimeException {
    ActiveContextCompactionRequiredException() {
        super("active context needs compaction");
    }
}
