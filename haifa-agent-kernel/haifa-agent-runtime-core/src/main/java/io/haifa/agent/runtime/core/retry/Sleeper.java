package io.haifa.agent.runtime.core.retry;

import java.time.Duration;

@FunctionalInterface
public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;

    static Sleeper threadSleep() {
        return duration -> Thread.sleep(duration);
    }
}
