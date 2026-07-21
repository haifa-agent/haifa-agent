package io.haifa.agent.runtime.core.control;

public final class CancellationObservedException extends RuntimeException {
    public CancellationObservedException() {
        super("run cancellation observed at tool safe point");
    }
}
