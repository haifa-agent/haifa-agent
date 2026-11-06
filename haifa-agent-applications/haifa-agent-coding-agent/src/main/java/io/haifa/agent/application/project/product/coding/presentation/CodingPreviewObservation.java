package io.haifa.agent.application.project.product.coding.presentation;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.execution.api.*;
import io.haifa.coding.api.*;
import io.haifa.coding.api.CodingProtocol.Preview;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded embedded projection over the existing preview publisher, with no execution ownership. */
final class CodingPreviewObservation implements CodingPreviewSubscription {
    private final CodingPreviewProjection projection = new CodingPreviewProjection();
    private final ArrayDeque<Preview> frames = new ArrayDeque<>();
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private ToolOutputPreviewPublisher.ToolOutputPreviewSubscription source;
    private boolean closed;
    private String failure;
    private int bytes;

    CodingPreviewObservation(ToolOutputPreviewPublisher publisher, AgentSessionId session, AgentRunId run) {
        var handle = publisher.subscribe(run, frame -> {
            synchronized (this) {
                if (closed) return;
                if (!run.equals(frame.runId())) {
                    fail("PREVIEW_SCOPE_MISMATCH");
                    return;
                }
                try {
                    var safe = projection.accept(frame);
                    if (safe == null) return;
                    var value = new Preview(
                            session.value(),
                            run.value(),
                            safe.toolCallId().value(),
                            safe.channel().name(),
                            safe.text(),
                            safe.outputTruncated(),
                            safe.previewDropped());
                    int length = json.writeValueAsBytes(value).length;
                    if (length > 16384 || bytes + length > 512 * 1024) {
                        fail("PREVIEW_BUDGET_EXCEEDED");
                        return;
                    }
                    frames.add(value);
                    bytes += length;
                    notifyAll();
                } catch (java.io.IOException | RuntimeException lost) {
                    fail("PREVIEW_UNAVAILABLE");
                }
            }
        });
        synchronized (this) {
            if (closed) handle.close();
            else source = handle;
        }
    }

    private void fail(String code) {
        failure = code;
        close();
    }

    public synchronized Preview poll(Duration timeout) throws InterruptedException {
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative preview timeout");
        long remaining = timeout.toNanos(), until = System.nanoTime() + remaining;
        while (frames.isEmpty() && !closed && remaining > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = until - System.nanoTime();
        }
        var value = frames.poll();
        if (value != null)
            try {
                bytes = Math.max(0, bytes - json.writeValueAsBytes(value).length);
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException("Preview encoding failed");
            }
        return value;
    }

    public synchronized boolean closed() {
        return closed;
    }

    public synchronized String unavailableReason() {
        return failure;
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        frames.clear();
        bytes = 0;
        if (source != null) source.close();
        notifyAll();
    }
}
