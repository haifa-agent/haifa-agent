package io.haifa.agent.application.project.product.coding.client;

import java.nio.file.Path;
import java.util.Map;

/** Public product-level assembly contract; concrete factories live in top-level product modules. */
@FunctionalInterface
public interface CodingAgentClientFactory {
    CodingAgentClient open(Path workspace, Path configuration, Map<String, String> environment);
}
