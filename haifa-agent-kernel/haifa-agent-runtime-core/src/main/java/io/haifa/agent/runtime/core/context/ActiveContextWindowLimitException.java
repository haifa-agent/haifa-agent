package io.haifa.agent.runtime.core.context;

/** Signals that unsummarized active history exceeded a hard in-memory projection boundary. */
public final class ActiveContextWindowLimitException extends RuntimeException {
    private final String limitKind;
    private final long limit;
    private final long observed;

    public ActiveContextWindowLimitException(String limitKind, long limit, long observed) {
        super("active context " + limitKind + " limit exceeded");
        this.limitKind = limitKind;
        this.limit = limit;
        this.observed = observed;
    }

    public String limitKind() {
        return limitKind;
    }

    public long limit() {
        return limit;
    }

    public long observed() {
        return observed;
    }
}
