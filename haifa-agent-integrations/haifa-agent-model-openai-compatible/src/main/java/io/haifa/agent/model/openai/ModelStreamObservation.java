package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelStreamSink;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks only whether consumable content or Tool intent crossed the provider boundary. */
public final class ModelStreamObservation {
    private final AtomicBoolean outputObserved = new AtomicBoolean();

    public ModelStreamSink observe(ModelStreamSink delegate) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        return event -> {
            if (event instanceof ModelStreamEvent.ContentDelta || event instanceof ModelStreamEvent.ToolCallDelta) {
                outputObserved.set(true);
            }
            return delegate.emit(event);
        };
    }

    public boolean outputObserved() {
        return outputObserved.get();
    }

    public ModelInvocationException annotate(ModelInvocationException failure) {
        if (!outputObserved()) return failure;
        return failure.category() == io.haifa.agent.model.api.ModelErrorCategory.CANCELLED
                ? failure.withOutputObserved()
                : failure.asPartialResponse();
    }
}
