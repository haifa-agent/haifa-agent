package io.haifa.agent.model.api;

/** Receives one normalized model stream event at a time. Implementations must not retain sensitive events blindly. */
@FunctionalInterface
public interface ModelStreamSink {

    ModelStreamControl emit(ModelStreamEvent event);

    static ModelStreamSink discarding() {
        return ignored -> ModelStreamControl.CONTINUE;
    }
}
