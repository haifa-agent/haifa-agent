package io.haifa.agent.model.anthropic;

import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelStreamSink;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks only whether consumable content or Tool intent crossed the provider boundary. */
final class ModelStreamObservation {
    private final AtomicBoolean outputObserved = new AtomicBoolean();

    ModelStreamSink observe(ModelStreamSink delegate) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        return event -> {
            if (event instanceof ModelStreamEvent.ContentDelta || event instanceof ModelStreamEvent.ToolCallDelta) {
                outputObserved.set(true);
            }
            return delegate.emit(event);
        };
    }

    boolean outputObserved() {
        return outputObserved.get();
    }

    ModelInvocationException annotate(ModelInvocationException failure) {
        if (!outputObserved()) return failure;
        return failure.category() == ModelErrorCategory.CANCELLED
                ? failure.withOutputObserved()
                : failure.asPartialResponse();
    }
}
