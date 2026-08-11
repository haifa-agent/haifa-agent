package io.haifa.agent.sdk.tool;

/**
 * Type-safe in-process Tool implemented with Java records.
 *
 * <p>The SDK derives bounded JSON Schemas from the declared input and output record types, adapts
 * the Tool to the unified Runtime Tool pipeline, and freezes its exact binding at Run creation.
 * Implementations should throw a Runtime exception, or a ToolInvocationException with an explicit
 * dispatch state, when invocation fails.
 */
public interface JavaTool<I extends Record, O extends Record> {
    JavaToolSpec<I, O> spec();

    O invoke(I input, JavaToolContext context);

    default String summarize(O output) {
        return spec().title() + " completed";
    }
}
