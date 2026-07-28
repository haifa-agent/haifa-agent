package io.haifa.agent.sdk.product;

public final class ProductAssemblyException extends IllegalStateException {
    private final String code;

    public ProductAssemblyException(String code, String safeMessage) {
        super(ProductValues.text(safeMessage, "safeMessage", 512));
        this.code = ProductValues.text(code, "code", 128);
    }

    public String code() {
        return code;
    }
}
