package io.haifa.agent.runtime.api;

import java.util.concurrent.Semaphore;

/**
 * Caller-owned, process-local Child admission capacity that can be shared by several Agents.
 * Closing an Agent does not close this object. Runtime retains each permit until the Child is terminal
 * and its execution tasks have settled, including tasks resumed after approval.
 */
public final class ChildRunCapacity {
    private final Semaphore permits;
    private final Object monitor = new Object();
    private long sequence;

    public ChildRunCapacity(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("maximum must be positive");
        permits = new Semaphore(maximum, true);
    }

    /** Runtime admission boundary; products configure and share this object rather than taking permits. */
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    /** Runtime settlement boundary; also wakes waiters belonging to other Agents. */
    public void release() {
        permits.release();
        signal();
    }

    /** Runtime notification boundary; no listener registrations or retained Agent references. */
    public void signal() {
        synchronized (monitor) {
            sequence++;
            monitor.notifyAll();
        }
    }

    public long wakeups() {
        synchronized (monitor) {
            return sequence;
        }
    }

    public void awaitWakeup(long observed, long millis) {
        synchronized (monitor) {
            if (sequence != observed) return;
            try {
                monitor.wait(Math.max(1, millis));
            } catch (InterruptedException interrupted) {
                // Runtime re-reads the authoritative control signal on the next pass.
            }
        }
    }
}
