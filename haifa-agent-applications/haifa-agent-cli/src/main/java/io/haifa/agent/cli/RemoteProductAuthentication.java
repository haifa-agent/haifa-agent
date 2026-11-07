package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationMethodView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProviderView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.coding.api.CodingClient;
import io.haifa.coding.api.CodingClientException;
import io.haifa.coding.api.CodingProtocol.*;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Safe remote status and logout. Interactive credential operations remain local. */
final class RemoteProductAuthentication implements CodingAuthenticationClient {
    private final CodingClient client;
    private final CommandTarget project;
    private final boolean initiallyRequired;

    RemoteProductAuthentication(CodingClient client) {
        this.client = client;
        this.project = new CommandTarget(client.connection().projectId(), null, null, null, null);
        this.initiallyRequired = status().connectionRequired();
    }

    private Authentication status() {
        var status = client.query(new Query("authentication", project, null, 64, null))
                .authentication();
        if (status == null) throw new CodingClientException("AUTH_STATUS_UNAVAILABLE", false);
        return status;
    }

    @Override
    public boolean connectionRequired() {
        return initiallyRequired;
    }

    @Override
    public List<CodingAuthenticationView> connections() {
        return status().connections().stream()
                .map(value -> new CodingAuthenticationView(
                        value.id(),
                        value.providerId(),
                        CodingAuthenticationView.Method.valueOf(value.method()),
                        CodingAuthenticationView.Status.valueOf(value.status()),
                        value.accountLabel(),
                        Optional.ofNullable(value.planType()),
                        value.resetAtEpochMillis() == null
                                ? OptionalLong.empty()
                                : OptionalLong.of(value.resetAtEpochMillis()),
                        value.unofficialLocalCompatibility()))
                .toList();
    }

    @Override
    public List<CodingAuthenticationProviderView> providers() {
        return status().providers().stream()
                .map(value -> new CodingAuthenticationProviderView(
                        value.id(),
                        value.displayName(),
                        CodingAuthenticationProviderView.Category.valueOf(value.category()),
                        value.connected(),
                        value.recommended(),
                        value.current(),
                        Optional.ofNullable(value.accountLabel()),
                        Optional.ofNullable(value.planLabel()),
                        value.methods().stream()
                                .map(method -> new CodingAuthenticationMethodView(
                                        method.id(),
                                        CodingAuthenticationMethodView.Type.valueOf(method.type()),
                                        false,
                                        Optional.of("AUTH_EXTERNAL_APPROVAL_REQUIRED")))
                                .toList(),
                        Optional.ofNullable(value.unavailableReason())))
                .toList();
    }

    @Override
    public CodingAuthenticationView loginCodexBrowser() {
        throw new IllegalStateException("AUTH_EXTERNAL_APPROVAL_REQUIRED");
    }

    @Override
    public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
        throw new IllegalStateException("AUTH_SECURE_INPUT_UNAVAILABLE");
    }

    @Override
    public boolean logout(String id) {
        String request = UUID.randomUUID().toString();
        var receipt = client.execute(
                        new Command("command", "logout", request, request, project, null, id, null, List.of()))
                .receipt();
        if ("accepted".equals(receipt.disposition())) return true;
        if ("CONNECTION_NOT_FOUND".equals(receipt.reasonCode())) return false;
        throw new CodingClientException(
                Objects.requireNonNullElse(receipt.reasonCode(), "AUTH_LOGOUT_FAILED"),
                "unknown".equals(receipt.disposition()));
    }
}
