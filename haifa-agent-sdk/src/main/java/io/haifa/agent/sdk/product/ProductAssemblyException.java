package io.haifa.agent.sdk.product;

import io.haifa.agent.sdk.api.HaifaAgentException;

public final class ProductAssemblyException extends HaifaAgentException {
    public ProductAssemblyException(String code, String safeMessage) {
        super(code, "product.assemble", "assembly", safeMessage);
    }
}
