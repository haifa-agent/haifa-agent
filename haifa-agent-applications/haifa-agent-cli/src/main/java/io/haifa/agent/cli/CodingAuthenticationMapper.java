package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.auth.localmodel.LocalModelConnectionView;
import java.util.Optional;

/** Central safe projection from shared model authentication into the Coding product port. */
final class CodingAuthenticationMapper {
    CodingAuthenticationView view(LocalModelConnectionView connection) {
        return new CodingAuthenticationView(
                connection.connectionId().value(),
                connection.providerId(),
                connection.method() == LocalModelConnectionView.Method.API_KEY
                        ? CodingAuthenticationView.Method.API_KEY
                        : "google-antigravity".equals(connection.providerId())
                                ? CodingAuthenticationView.Method.ANTIGRAVITY_SUBSCRIPTION
                                : CodingAuthenticationView.Method.CHATGPT_SUBSCRIPTION,
                switch (connection.status()) {
                    case AUTHENTICATED -> CodingAuthenticationView.Status.AUTHENTICATED;
                    case REAUTH_REQUIRED -> CodingAuthenticationView.Status.REAUTH_REQUIRED;
                    case RATE_LIMITED -> CodingAuthenticationView.Status.RATE_LIMITED;
                },
                connection.accountLabel(),
                Optional.empty(),
                connection.expiresAtEpochMillis(),
                connection.unofficialLocalCompatibility());
    }
}
