package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.auth.localmodel.InMemoryWindowsCredentialManagerClient;
import io.haifa.agent.auth.localmodel.LocalModelAuthenticationService;
import io.haifa.agent.auth.localmodel.WindowsLocalModelAuthStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class CliAuthenticationTestFixture {
    static WindowsLocalModelAuthStore store() {
        return new WindowsLocalModelAuthStore(new InMemoryWindowsCredentialManagerClient(), new ObjectMapper());
    }

    static CliCodingAuthenticationClient client(
            WindowsLocalModelAuthStore store,
            String reference,
            String provider,
            Map<String, String> environment,
            List<String> references) {
        var service = new LocalModelAuthenticationService(
                store,
                Optional.empty(),
                ignored -> {
                    throw new AssertionError("Credential resolution is not expected");
                },
                environment::get);
        return new CliCodingAuthenticationClient(service, reference, provider, references);
    }

    private CliAuthenticationTestFixture() {}
}
