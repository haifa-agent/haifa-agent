package io.haifa.agent.model.api;

/** Immutable token limits for one exact model binding. */
public record ModelExecutionLimits(int contextWindowTokens, int minimumOutputTokens, int maximumOutputTokens) {
    public ModelExecutionLimits {
        if (contextWindowTokens < 1
                || minimumOutputTokens < 1
                || maximumOutputTokens < minimumOutputTokens
                || maximumOutputTokens > contextWindowTokens) {
            throw new IllegalArgumentException("model execution token limits are invalid");
        }
    }

    /**
     * Returns the input budget after the planned output reserve and fixed input overhead are removed.
     * This method deliberately rejects an impossible plan instead of silently clipping either budget.
     */
    public int effectiveInputBudgetTokens(int outputReserveTokens, int fixedInputOverheadTokens) {
        if (outputReserveTokens < minimumOutputTokens || outputReserveTokens > maximumOutputTokens) {
            throw new IllegalArgumentException("outputReserveTokens must be within the binding output range");
        }
        if (fixedInputOverheadTokens < 0) {
            throw new IllegalArgumentException("fixedInputOverheadTokens must not be negative");
        }
        long budget = (long) contextWindowTokens - outputReserveTokens - fixedInputOverheadTokens;
        if (budget < 0) {
            throw new IllegalArgumentException("output reserve and input overhead exceed the context window");
        }
        return Math.toIntExact(budget);
    }
}
