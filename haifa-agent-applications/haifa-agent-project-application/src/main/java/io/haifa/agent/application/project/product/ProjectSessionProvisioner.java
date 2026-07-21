package io.haifa.agent.application.project.product;

/** Creates the existing Core session aggregate before Runtime start; the product does not duplicate that aggregate. */
@FunctionalInterface
public interface ProjectSessionProvisioner {
    void provision(ProjectProductSession session);
}
