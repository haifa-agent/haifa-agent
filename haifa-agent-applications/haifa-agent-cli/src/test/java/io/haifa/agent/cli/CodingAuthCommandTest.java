package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.client.*;
import io.haifa.agent.auth.localmodel.LocalModelAuthReference;
import io.haifa.agent.auth.localmodel.StoredApiKeyCredential;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

class CodingAuthCommandTest {
    private static final PrintStream SILENT = new PrintStream(OutputStream.nullOutputStream());
    private static final String REFERENCE = "model-auth://deepseek/default";

    @Test
    void localConnectStatusAndExplicitLogoutUseTheSharedStoreAndWipeInput() {
        var store = CliAuthenticationTestFixture.store();
        char[] secret = "synthetic-local-key".toCharArray();
        var command = new CodingAuthCommand(
                args -> CliAuthenticationTestFixture.client(store, REFERENCE, "deepseek", Map.of(), List.of(REFERENCE)),
                () -> secret);
        assertThat(command.run(new String[] {"connect", "--provider", "deepseek"}, SILENT, SILENT))
                .isZero();
        assertThat(secret).containsOnly('\0');
        assertThat(((StoredApiKeyCredential) store.find(LocalModelAuthReference.parse(REFERENCE))
                                .orElseThrow())
                        .apiKey())
                .isEqualTo("synthetic-local-key");
        assertThat(command.run(new String[] {"status"}, SILENT, SILENT)).isZero();
        assertThat(store.listSafe()).hasSize(1);
        assertThat(command.run(new String[] {"logout", "--connection", REFERENCE}, SILENT, SILENT))
                .isZero();
        assertThat(store.listSafe()).isEmpty();
    }

    @Test
    void repairingBailianKeyPreservesItsSavedWorkspaceAndRegion() {
        String provider = "aliyun-bailian";
        String reference = "model-auth://" + provider + "/default";
        var store = CliAuthenticationTestFixture.store();
        var client = CliAuthenticationTestFixture.client(store, reference, provider, Map.of(), List.of(reference));
        var attributes = Map.of("workspace_id", "synthetic-workspace", "region", "singapore");
        client.saveApiKey(provider, "synthetic-old-key".toCharArray(), attributes);
        char[] replacement = "synthetic-replacement-key".toCharArray();
        var command = new CodingAuthCommand(args -> client, () -> replacement);

        assertThat(command.run(new String[] {"connect", "--provider", provider}, SILENT, SILENT))
                .isZero();
        var stored = (StoredApiKeyCredential)
                store.find(LocalModelAuthReference.parse(reference)).orElseThrow();
        assertThat(stored.apiKey()).isEqualTo("synthetic-replacement-key");
        assertThat(stored.attributes()).isEqualTo(attributes);
        assertThat(replacement).containsOnly('\0');
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
            int code = command.run(new String[] {"connect", "--provider", "deepseek"}, SILENT, SILENT);
            assertThat(code).isEqualTo(input.isEmpty() ? 130 : 1);
            assertThat(secret).isEqualTo(new char[input.length()]);
            assertThat(((StoredApiKeyCredential) store.find(LocalModelAuthReference.parse(REFERENCE))
                                    .orElseThrow())
                            .apiKey())
                    .isEqualTo("synthetic-existing-key");
        }
    }

    @Test
    void helpAndRejectedArgumentsNeverOpenTheAuthenticationAssembly() {
        var command = new CodingAuthCommand(
                args -> {
                    throw new AssertionError("No assembly expected");
                },
                () -> {
                    throw new AssertionError("No input expected");
                });
        assertThat(command.run(new String[] {"--help"}, SILENT, SILENT)).isZero();
        for (String[] args : List.of(
                new String[] {"connect", "--provider", "deepseek", "--api-key", "synthetic-argument-key"},
                new String[] {"connect"},
                new String[] {"logout"},
                new String[] {"status", "--method", "device"})) {
            assertThat(command.run(args, SILENT, SILENT)).isEqualTo(2);
        }
    }

    @Test
    void backendFailureReturnsNonzeroWithoutReadingInput() {
        var command = new CodingAuthCommand(
                args -> {
                    throw new IllegalStateException("AUTH_SYNTHETIC_SECRET_CANARY");
                },
                () -> {
                    throw new AssertionError("No input expected");
                });
        assertThat(command.run(new String[] {"status"}, SILENT, SILENT)).isEqualTo(1);
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
        assertThat(command.run(
                        new String[] {"connect", "--provider", "opencode-go", "--method", "device"}, SILENT, SILENT))
                .isZero();
        assertThat(methods).containsExactly("loginDevice");
    }

    @Test
    void mainRoutesAuthHelpBeforeTerminalConfigurationAndRuntime() {
        assertThat(new HaifaCliMain().run(new String[] {"auth", "--help"}, SILENT, SILENT))
                .isZero();
    }
}
