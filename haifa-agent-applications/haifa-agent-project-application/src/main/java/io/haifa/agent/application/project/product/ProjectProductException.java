package io.haifa.agent.application.project.product;

import java.util.Objects;

public final class ProjectProductException extends RuntimeException {
    private final String code;

    public ProjectProductException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public String code() {
        return code;
    }
}
