package io.haifa.agent.application.project.product.coding.delivery;

public record CodingDeliveryProfile(boolean allowBlockedValidation) {
    public static CodingDeliveryProfile safeDefault() {
        return new CodingDeliveryProfile(false);
    }
}
