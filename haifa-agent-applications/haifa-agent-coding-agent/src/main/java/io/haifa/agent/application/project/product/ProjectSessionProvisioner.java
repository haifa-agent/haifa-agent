package io.haifa.agent.application.project.product;

import java.util.Map;

/** Creates the existing Core session aggregate before Runtime start; the product does not duplicate that aggregate. */
@FunctionalInterface
public interface ProjectSessionProvisioner {
    void provision(ProjectProductSession session, Map<String, Object> metadata);

    default void provision(ProjectProductSession session) {
        provision(session, Map.of());
    }
}
