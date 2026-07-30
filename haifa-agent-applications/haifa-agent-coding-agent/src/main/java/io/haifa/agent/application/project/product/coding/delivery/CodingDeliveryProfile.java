package io.haifa.agent.application.project.product.coding.delivery;

public record CodingDeliveryProfile(
        int modelCallsReservePercent,
        int toolCallsReservePercent,
        int wallTimeReservePercent,
        boolean allowBlockedValidation) {
    public CodingDeliveryProfile {
        range(modelCallsReservePercent, "modelCallsReservePercent");
        range(toolCallsReservePercent, "toolCallsReservePercent");
        range(wallTimeReservePercent, "wallTimeReservePercent");
    }

    public static CodingDeliveryProfile safeDefault() {
        return new CodingDeliveryProfile(20, 25, 20, false);
    }

    private static void range(int value, String field) {
        if (value < 1 || value > 50) throw new IllegalArgumentException(field + " must be between 1 and 50");
    }
}
