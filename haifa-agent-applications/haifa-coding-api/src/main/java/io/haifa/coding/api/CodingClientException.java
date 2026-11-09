package io.haifa.coding.api;
/** Safe protocol/transport failure; unknown outcomes never authorize automatic command replay. */
public final class CodingClientException extends RuntimeException {
    private final String code;
    private final boolean outcomeUnknown;

    public CodingClientException(String code, boolean outcomeUnknown) {
        super(code);
        this.code = code;
        this.outcomeUnknown = outcomeUnknown;
    }

    public String code() {
        return code;
    }

    public boolean outcomeUnknown() {
        return outcomeUnknown;
    }
}
