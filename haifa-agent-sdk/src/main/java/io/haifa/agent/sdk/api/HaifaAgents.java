package io.haifa.agent.sdk.api;

import io.haifa.agent.sdk.product.ProductProfile;

/** Entry point for assembling one Agent product from a trusted Product Profile. */
public final class HaifaAgents {
    private HaifaAgents() {}

    public static HaifaAgentBuilder builder() {
        return new HaifaAgentBuilder();
    }

    public static HaifaAgentBuilder builder(ProductProfile profile) {
        return new HaifaAgentBuilder().product(profile);
    }
}
