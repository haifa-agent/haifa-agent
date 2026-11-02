package io.haifa.agent.cli.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RemoteCodingAuthenticationClientTest {

    @Test
    void exposesSafeStatusReadAndLogoutOnly() throws Exception {
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "auth/status" -> FakeCodingHost.Reply.result(status());
            case "auth/logout" -> FakeCodingHost.Reply.result(map("removed", true));
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                CodingAuthenticationClient authentication = client.authentication();
                assertThat(authentication.connectionRequired()).isTrue();
                assertThat(authentication.apiKeyProviderId()).isEqualTo("deepseek");
                assertThat(authentication.apiKeyConnectionSupported()).isFalse();
                assertThat(authentication.codexConnectionSupported()).isFalse();
                assertThat(authentication.antigravityConnectionSupported()).isFalse();

                var providers = authentication.providers();
                assertThat(providers).hasSize(1);
                assertThat(providers.getFirst().providerId()).isEqualTo("deepseek");
                assertThat(providers.getFirst().methods().getFirst().type().name())
                        .isEqualTo("API_KEY");

                var connections = authentication.connections();
                assertThat(connections).hasSize(1);
                assertThat(connections.getFirst().connectionId()).isEqualTo("connection-1");
                assertThat(connections.getFirst().status().name()).isEqualTo("AUTHENTICATED");

                assertThat(authentication.logout("connection-1")).isTrue();
                assertThat(host.calls("auth/logout")
                                .getFirst()
                                .params()
                                .get("connectionId")
                                .textValue())
                        .isEqualTo("connection-1");
                assertThat(host.calls("auth/logout").getFirst().clientId()).isEqualTo("client-1");
            }
        }
    }

    @Test
    void credentialWriteAndInteractiveLoginAreRejectedBeforeAnyRequest() throws Exception {
        try (FakeCodingHost host = FakeCodingHost.start(call -> {
            if (call.method().equals("initialize")) {
                return FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            }
            return FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                CodingAuthenticationClient authentication = client.authentication();
                assertThatThrownBy(() -> authentication.saveApiKey("deepseek", new char[] {'s', 'e', 'c'}))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(RemoteCodingAuthenticationClient.CREDENTIAL_WRITE_UNSUPPORTED);
                assertThatThrownBy(authentication::loginCodexBrowser)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(RemoteCodingAuthenticationClient.LOGIN_UNSUPPORTED);
                assertThatThrownBy(() -> authentication.loginCodexDevice(ignored -> {}))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(RemoteCodingAuthenticationClient.LOGIN_UNSUPPORTED);
                assertThat(host.calls("auth/status")).isEmpty();
                assertThat(host.calls("auth/saveApiKey")).isEmpty();
                assertThat(host.calls("auth/login")).isEmpty();
            }
        }
    }

    @Test
    void malformedAuthStatusFailsSafely() throws Exception {
        Map<String, Object> invalid = status();
        invalid.put("credentialWrite", true);
        try (FakeCodingHost host = FakeCodingHost.start(call -> switch (call.method()) {
            case "initialize" -> FakeCodingHost.Reply.result(FakeCodingHost.initializeResult("client-1", "project-1"));
            case "auth/status" -> FakeCodingHost.Reply.result(invalid);
            default -> FakeCodingHost.Reply.error("REMOTE_METHOD_UNSUPPORTED");
        })) {
            try (RemoteCodingSessionClient client = RemoteCodingSessionClient.connect(host.uri())) {
                assertThatThrownBy(() -> client.authentication().connections())
                        .isInstanceOfSatisfying(ProjectProductException.class, thrown -> assertThat(thrown.code())
                                .isEqualTo("REMOTE_PROTOCOL_INCOMPATIBLE"));
            }
        }
    }

    private static Map<String, Object> status() {
        return map(
                "connectionRequired",
                true,
                "apiKeyProviderId",
                "deepseek",
                "apiKeyConnectionSupported",
                true,
                "providers",
                List.of(map(
                        "providerId",
                        "deepseek",
                        "displayName",
                        "DeepSeek",
                        "category",
                        "API_KEY",
                        "connected",
                        true,
                        "recommended",
                        true,
                        "current",
                        true,
                        "accountLabel",
                        "Work",
                        "planLabel",
                        null,
                        "methods",
                        List.of(map("id", "api-key", "type", "API_KEY", "available", true, "unavailableReason", null)),
                        "unavailableReason",
                        null)),
                "connections",
                List.of(map(
                        "connectionId",
                        "connection-1",
                        "providerId",
                        "deepseek",
                        "method",
                        "API_KEY",
                        "status",
                        "AUTHENTICATED",
                        "accountLabel",
                        "Work",
                        "planType",
                        null,
                        "resetAtEpochMillis",
                        null,
                        "unofficialLocalCompatibility",
                        false)),
                "credentialWrite",
                false,
                "credentialLogin",
                false,
                "logout",
                true);
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return values;
    }
}
