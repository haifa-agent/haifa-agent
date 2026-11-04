package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.client.*;
import io.haifa.agent.auth.localmodel.LocalModelAuthReference;
import io.haifa.agent.auth.localmodel.StoredApiKeyCredential;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class CodingAuthCommandTest {
    private static final String REFERENCE = "model-auth://deepseek/default";

    @Test
    void localConnectStatusAndExplicitLogoutUseTheSharedStoreWithoutLeakingOrRetainingInput() {
        var store = CliAuthenticationTestFixture.store();
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        char[] secret = "synthetic-local-key".toCharArray();
        var command = new CodingAuthCommand(
                args -> CliAuthenticationTestFixture.client(store, REFERENCE, "deepseek", Map.of(), List.of(REFERENCE)),
                () -> secret);
        assertThat(command.run(new String[] {"connect", "--provider", "deepseek"}, stream(output), stream(error)))
                .isZero();
        assertThat(secret).containsOnly('\0');
        assertThat(((StoredApiKeyCredential) store.find(LocalModelAuthReference.parse(REFERENCE))
                                .orElseThrow())
                        .apiKey())
                .isEqualTo("synthetic-local-key");
        assertThat(command.run(new String[] {"status"}, stream(output), stream(error)))
                .isZero();
        assertThat(store.listSafe()).hasSize(1);
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains(REFERENCE, "/account")
                .doesNotContain("synthetic-local-key");
        assertThat(error.size()).isZero();
        assertThat(command.run(new String[] {"logout", "--connection", REFERENCE}, stream(output), stream(error)))
                .isZero();
        assertThat(store.listSafe()).isEmpty();
    }

    @Test
    void cancelledAndInvalidInputKeepTheExistingCredential() {
        for (String input : List.of("", "   ")) {
            var store = CliAuthenticationTestFixture.store();
            var initial =
                    CliAuthenticationTestFixture.client(store, REFERENCE, "deepseek", Map.of(), List.of(REFERENCE));
            initial.saveApiKey("deepseek", "synthetic-existing-key".toCharArray());
            char[] secret = input.toCharArray();
            var command = new CodingAuthCommand(args -> initial, () -> secret);
            var output = new ByteArrayOutputStream();
            int code = command.run(new String[] {"connect", "--provider", "deepseek"}, stream(output), stream(output));
            assertThat(code).isEqualTo(input.isEmpty() ? 130 : 1);
            assertThat(secret).isEqualTo(new char[input.length()]);
            assertThat(((StoredApiKeyCredential) store.find(LocalModelAuthReference.parse(REFERENCE))
                                    .orElseThrow())
                            .apiKey())
                    .isEqualTo("synthetic-existing-key");
            assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("synthetic-existing-key");
        }
    }

    @Test
    void helpAndRejectedArgumentsNeverOpenTheAuthenticationAssemblyOrEchoValues() {
        var command = new CodingAuthCommand(
                args -> {
                    throw new AssertionError("No assembly expected");
                },
                () -> {
                    throw new AssertionError("No input expected");
                });
        var output = new ByteArrayOutputStream();
        assertThat(command.run(new String[] {"--help"}, stream(output), stream(output)))
                .isZero();
        for (String[] args : List.of(
                new String[] {"connect", "--provider", "deepseek", "--api-key", "synthetic-argument-key"},
                new String[] {"connect"},
                new String[] {"logout"},
                new String[] {"status", "--method", "device"})) {
            assertThat(command.run(args, stream(output), stream(output))).isEqualTo(2);
        }
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("synthetic-argument-key");
    }

    @Test
    void evenAuthLookingBackendTextIsNotPrintedAsAStableReason() {
        var output = new ByteArrayOutputStream();
        var command = new CodingAuthCommand(
                args -> {
                    throw new IllegalStateException("AUTH_SYNTHETIC_SECRET_CANARY");
                },
                () -> {
                    throw new AssertionError("No input expected");
                });
        assertThat(command.run(new String[] {"status"}, stream(output), stream(output)))
                .isEqualTo(1);
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("AUTH_LOCAL_COMMAND_FAILED")
                .doesNotContain("AUTH_SYNTHETIC_SECRET_CANARY");
    }

    @Test
    void deviceLoginDelegatesToTheExistingClientAndNeverReadsAnApiKey() {
        var methods = new ArrayList<String>();
        CodingAuthenticationClient client = (CodingAuthenticationClient) Proxy.newProxyInstance(
                CodingAuthenticationClient.class.getClassLoader(),
                new Class<?>[] {CodingAuthenticationClient.class},
                (proxy, method, args) -> {
                    methods.add(method.getName());
                    if (!method.getName().equals("loginDevice")) throw new AssertionError(method.getName());
                    assertThat(args[0]).isEqualTo("opencode-go");
                    return new CodingAuthenticationView(
                            "model-auth://opencode-go/default",
                            "opencode-go",
                            CodingAuthenticationView.Method.CHATGPT_SUBSCRIPTION,
                            CodingAuthenticationView.Status.AUTHENTICATED,
                            "Synthetic account",
                            Optional.empty(),
                            OptionalLong.empty(),
                            false);
                });
        var command = new CodingAuthCommand(args -> client, () -> {
            throw new AssertionError("No API-key input");
        });
        var output = new ByteArrayOutputStream();
        assertThat(command.run(
                        new String[] {"connect", "--provider", "opencode-go", "--method", "device"},
                        stream(output),
                        stream(output)))
                .isZero();
        assertThat(methods).containsExactly("loginDevice");
    }

    @Test
    void mainRoutesAuthHelpBeforeTerminalConfigurationAndRuntime() {
        var output = new ByteArrayOutputStream();
        assertThat(new HaifaCliMain().run(new String[] {"auth", "--help"}, stream(output), stream(output)))
                .isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Usage: haifa auth");
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }
}
