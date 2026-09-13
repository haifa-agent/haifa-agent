package io.haifa.agent.auth.localmodel;

import java.util.Objects;

/** Structured product exception for Windows Credential Manager failures. */
public class WindowsCredentialManagerException extends RuntimeException {
    public enum Reason {
        MISSING,
        ACCESS_DENIED,
        ITEM_TOO_LARGE,
        UNAVAILABLE,
        STORE_FAILURE
    }

    private final Reason reason;
    private final int errorCode;

    public WindowsCredentialManagerException(Reason reason, String message) {
        this(reason, 0, message, null);
    }

    public WindowsCredentialManagerException(Reason reason, int errorCode, String message) {
        this(reason, errorCode, message, null);
    }

    public WindowsCredentialManagerException(Reason reason, int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.errorCode = errorCode;
    }

    public Reason reason() {
        return reason;
    }

    public int errorCode() {
        return errorCode;
    }

    public static WindowsCredentialManagerException fromErrorCode(String operation, int errorCode) {
        Reason reason = mapErrorCode(errorCode);
        return new WindowsCredentialManagerException(
                reason,
                errorCode,
                "Windows Credential Manager " + operation + " failed with error " + errorCode + " (" + reason + ")");
    }

    public static Reason mapErrorCode(int errorCode) {
        return switch (errorCode) {
            case 1168 -> Reason.MISSING; // ERROR_NOT_FOUND
            case 5 -> Reason.ACCESS_DENIED; // ERROR_ACCESS_DENIED
            case 87, 111, 206 ->
                Reason.ITEM_TOO_LARGE; // ERROR_INVALID_PARAMETER, ERROR_BUFFER_OVERFLOW, ERROR_FILENAME_EXCED_RANGE
            case 1312, 1753, 1722 ->
                Reason.UNAVAILABLE; // ERROR_NO_SUCH_LOGON_SESSION, RPC_S_SERVER_UNAVAILABLE, RPC_S_SERVER_TOO_BUSY
            default -> Reason.STORE_FAILURE;
        };
    }
}
