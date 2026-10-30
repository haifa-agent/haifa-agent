package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.project.domain.ProjectId;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class CodingHostDispatcherTest {
    @Test
    void unexpectedBackendMessagesAreSanitizedAndNeverReachTheWire() {
        CodingSessionClient client = (CodingSessionClient) Proxy.newProxyInstance(
                CodingSessionClient.class.getClassLoader(),
                new Class<?>[] {CodingSessionClient.class},
                (proxy, method, arguments) -> {
                    throw new IllegalStateException("CANARY_SECRET_PATH D:\\private\\credentials.txt");
                });
        CodingHostDispatcher dispatcher =
                new CodingHostDispatcher(new ProjectId("project-1"), client, null, null, null, null, null);

        assertThatThrownBy(() -> dispatcher.dispatch(
                        "client-1",
                        "session/reconcile",
                        new ObjectMapper().createObjectNode().put("sessionId", "session-1")))
                .isInstanceOf(CodingHostException.class)
                .satisfies(exception -> {
                    CodingHostException host = (CodingHostException) exception;
                    org.assertj.core.api.Assertions.assertThat(host.reasonCode())
                            .isEqualTo("OPERATION_REJECTED");
                    org.assertj.core.api.Assertions.assertThat(host.getMessage())
                            .doesNotContain("CANARY_SECRET_PATH", "credentials.txt");
                });
    }
}
