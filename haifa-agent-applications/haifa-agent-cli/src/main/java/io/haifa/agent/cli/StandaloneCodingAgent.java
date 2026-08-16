package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.client.CodingAgentClient;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.project.domain.ProjectId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Closeable handle for one fully assembled standalone Coding Agent product instance. */
public final class StandaloneCodingAgent implements CodingAgentClient {
    private final LocalCodingAgent localAgent;
    private final CodingSessionClient client;
    private final StandaloneCodingAgentMetadata metadata;
    private final AtomicBoolean closed = new AtomicBoolean();

    StandaloneCodingAgent(
            LocalCodingAgent localAgent, CodingSessionClient client, StandaloneCodingAgentMetadata metadata) {
        this.localAgent = Objects.requireNonNull(localAgent, "localAgent must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
    }

    public ProjectId projectId() {
        requireOpen();
        return localAgent.projectId();
    }

    public CodingSessionClient client() {
        requireOpen();
        return client;
    }

    public StandaloneCodingAgentMetadata metadata() {
        requireOpen();
        return metadata;
    }

    boolean closed() {
        return closed.get();
    }

    LocalCodingAgent localAgent() {
        requireOpen();
        return localAgent;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) localAgent.close();
    }

    @Override
    public String toString() {
        return "StandaloneCodingAgent[closed=" + closed.get() + ", assemblyDigest=" + metadata.assemblyDigest() + "]";
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("standalone Coding Agent is closed");
    }
}
