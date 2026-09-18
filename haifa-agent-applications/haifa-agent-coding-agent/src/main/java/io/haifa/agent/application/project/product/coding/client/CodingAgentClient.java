package io.haifa.agent.application.project.product.coding.client;

import io.haifa.agent.project.domain.ProjectId;

/** Closeable standard Coding Agent product client returned by a highest-level assembly factory. */
public interface CodingAgentClient extends AutoCloseable {
    ProjectId projectId();

    CodingSessionClient client();

    CodingAgentClientMetadata metadata();

    default String assemblyDigest() {
        return metadata().assemblyDigest();
    }

    @Override
    void close();
}
