package io.haifa.agent.cli.remote;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProviderView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Remote, read-only view of Coding authentication; credential writes and interactive logins stay local. */
public final class RemoteCodingAuthenticationClient implements CodingAuthenticationClient {
    static final String CREDENTIAL_WRITE_UNSUPPORTED = "AUTH_REMOTE_CREDENTIAL_WRITE_UNSUPPORTED";
    static final String LOGIN_UNSUPPORTED = "AUTH_REMOTE_LOGIN_UNSUPPORTED";

    private final RemoteRpcTransport transport;
    private final RemoteWireCodec codec;

    RemoteCodingAuthenticationClient(RemoteRpcTransport transport, RemoteWireCodec codec) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
    }

    private JsonNode status() {
        JsonNode result = transport.call("auth/status", Map.of(), false);
        return decode(() -> {
            RemoteWireCodec.requireObject(result, "authentication status");
            if (RemoteWireCodec.requiredBoolean(result, "credentialWrite")
                    || RemoteWireCodec.requiredBoolean(result, "credentialLogin")) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
            return result;
        });
    }

    @Override
    public boolean connectionRequired() {
        JsonNode result = status();
        return decode(() -> codec.authenticationConnectionRequired(result));
    }

    @Override
    public String apiKeyProviderId() {
        JsonNode result = status();
        return decode(() -> codec.authenticationApiKeyProviderId(result));
    }

    @Override
    public boolean apiKeyConnectionSupported() {
        return false;
    }

    @Override
    public boolean codexConnectionSupported() {
        return false;
    }

    @Override
    public boolean antigravityConnectionSupported() {
        return false;
    }

    @Override
    public List<CodingAuthenticationProviderView> providers() {
        JsonNode result = status();
        return decode(() -> codec.authenticationProviders(result));
    }

    @Override
    public List<CodingAuthenticationView> connections() {
        JsonNode result = status();
        return decode(() -> codec.authenticationConnections(result));
    }

    @Override
    public CodingAuthenticationView loginCodexBrowser() {
        throw new IllegalStateException(LOGIN_UNSUPPORTED);
    }

    @Override
    public CodingAuthenticationView loginCodexBrowser(
            Consumer<io.haifa.agent.application.project.product.coding.client.CodingBrowserLoginView> instructions) {
        throw new IllegalStateException(LOGIN_UNSUPPORTED);
    }

    @Override
    public CodingAuthenticationView loginCodexBrowser(
            Consumer<io.haifa.agent.application.project.product.coding.client.CodingBrowserLoginView> instructions,
            Consumer<io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProgressView>
                    progress) {
        throw new IllegalStateException(LOGIN_UNSUPPORTED);
    }

    @Override
    public CodingAuthenticationView loginCodexDevice(
            Consumer<io.haifa.agent.application.project.product.coding.client.CodingDeviceLoginView> instructions) {
        throw new IllegalStateException(LOGIN_UNSUPPORTED);
    }

    @Override
    public CodingAuthenticationView loginCodexDevice(
            Consumer<io.haifa.agent.application.project.product.coding.client.CodingDeviceLoginView> instructions,
            Consumer<io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProgressView>
                    progress) {
        throw new IllegalStateException(LOGIN_UNSUPPORTED);
    }

    @Override
    public CodingAuthenticationView loginAntigravityBrowser(
            Consumer<io.haifa.agent.application.project.product.coding.client.CodingBrowserLoginView> instructions,
            Consumer<io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProgressView>
                    progress) {
        throw new IllegalStateException(LOGIN_UNSUPPORTED);
    }

    @Override
    public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
        throw new IllegalStateException(CREDENTIAL_WRITE_UNSUPPORTED);
    }

    @Override
    public boolean logout(String connectionId) {
        JsonNode result = transport.call("auth/logout", RemoteRpcTransport.params("connectionId", connectionId), true);
        return decode(() -> {
            RemoteWireCodec.requireObject(result, "logout receipt");
            return RemoteWireCodec.requiredBoolean(result, "removed");
        });
    }

    private static <T> T decode(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (ProjectProductException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
    }
}
