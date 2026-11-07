package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.client.*;
import io.haifa.agent.execution.api.ToolOutputPreviewPublisher;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.coding.host.internal.LocalCodingAgent;

/** Legacy CLI compatibility handle. New products use io.haifa.coding.host. */
public final class StandaloneCodingAgent implements CodingAgentClient {
    private final io.haifa.coding.host.internal.StandaloneCodingAgent delegate;

    StandaloneCodingAgent(io.haifa.coding.host.internal.StandaloneCodingAgent delegate) {
        this.delegate = delegate;
    }

    io.haifa.coding.host.internal.StandaloneCodingAgent unwrap() {
        return delegate;
    }

    public ProjectId projectId() {
        return delegate.projectId();
    }

    public CodingSessionClient client() {
        return delegate.client();
    }

    public CodingAuthenticationClient authentication() {
        return delegate.authentication();
    }

    public ToolOutputPreviewPublisher toolOutputPreviewPublisher() {
        return delegate.toolOutputPreviewPublisher();
    }

    public StandaloneCodingAgentMetadata metadata() {
        var m = delegate.metadata();
        return new StandaloneCodingAgentMetadata(
                m.providerId(), m.modelId(), m.modelBindingId(), m.apiStyle(), m.assemblyDigest());
    }

    boolean closed() {
        return delegate.closed();
    }

    LocalCodingAgent localAgent() {
        return delegate.localAgent();
    }

    public void close() {
        delegate.close();
    }

    public String toString() {
        return delegate.toString();
    }
}
