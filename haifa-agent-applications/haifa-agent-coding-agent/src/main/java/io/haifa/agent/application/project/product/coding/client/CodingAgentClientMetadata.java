package io.haifa.agent.application.project.product.coding.client;

/** Safe allowlisted identity exposed with the standard Coding Agent client. */
public interface CodingAgentClientMetadata {
    String providerId();

    String modelId();

    String modelBindingId();

    String apiStyle();

    String assemblyDigest();
}
