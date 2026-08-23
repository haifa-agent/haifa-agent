package io.haifa.agent.application.project.product.coding.client;

import java.util.List;
import java.util.function.Consumer;

/** Product authentication use cases. Implementations live in the highest application assembly. */
public interface CodingAuthenticationClient {
    default boolean connectionRequired() {
        return false;
    }

    default String apiKeyProviderId() {
        return "openai";
    }

    default boolean apiKeyConnectionSupported() {
        return true;
    }

    List<CodingAuthenticationView> connections();

    CodingAuthenticationView loginCodexBrowser();

    default CodingAuthenticationView loginCodexDevice(Consumer<CodingDeviceLoginView> instructions) {
        throw new IllegalStateException("AUTH_DEVICE_CODE_UNAVAILABLE");
    }

    CodingAuthenticationView saveApiKey(String providerId, char[] apiKey);

    boolean logout(String connectionId);

    static CodingAuthenticationClient unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements CodingAuthenticationClient {
        INSTANCE;

        @Override
        public List<CodingAuthenticationView> connections() {
            return List.of();
        }

        @Override
        public CodingAuthenticationView loginCodexBrowser() {
            throw new IllegalStateException("AUTH_EXTERNAL_APPROVAL_REQUIRED");
        }

        @Override
        public CodingAuthenticationView loginCodexDevice(Consumer<CodingDeviceLoginView> instructions) {
            throw new IllegalStateException("AUTH_EXTERNAL_APPROVAL_REQUIRED");
        }

        @Override
        public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
            throw new IllegalStateException("AUTH_SECURE_INPUT_UNAVAILABLE");
        }

        @Override
        public boolean logout(String connectionId) {
            return false;
        }
    }
}
