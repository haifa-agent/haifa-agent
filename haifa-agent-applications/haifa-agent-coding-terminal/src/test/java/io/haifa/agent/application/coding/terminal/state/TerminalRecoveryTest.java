package io.haifa.agent.application.coding.terminal.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalRecoveryTest {

    @Test
    void modelPaymentRequiredMapsToUserActionRequiredWithStrictPrompt() {
        TerminalRecovery recovery = TerminalRecovery.fromCode("MODEL_PAYMENT_REQUIRED");

        assertThat(recovery.category()).isEqualTo(TerminalRecovery.Category.USER_ACTION_REQUIRED);
        assertThat(recovery.code()).isEqualTo("MODEL_PAYMENT_REQUIRED");
        assertThat(recovery.action()).isEqualTo("请检查 Provider 账户余额、套餐、模型授权或账单状态后重试");
    }

    @Test
    void modelAuthenticationRequiredMapsToUserActionRequiredWithLoginOrSwitchPrompt() {
        TerminalRecovery recovery = TerminalRecovery.fromCode("MODEL_AUTHENTICATION_REQUIRED");

        assertThat(recovery.category()).isEqualTo(TerminalRecovery.Category.USER_ACTION_REQUIRED);
        assertThat(recovery.code()).isEqualTo("MODEL_AUTHENTICATION_REQUIRED");
        assertThat(recovery.action())
                .isEqualTo(
                        "Not signed in or credentials unavailable; run /connect to connect or sign in again, or run /model to switch models.");
    }

    @Test
    void knownErrorCodesPreserveActionablePresentation() {
        TerminalRecovery rateLimited = TerminalRecovery.fromCode("MODEL_RATE_LIMITED");
        assertThat(rateLimited.category()).isEqualTo(TerminalRecovery.Category.RETRYABLE);

        TerminalRecovery timeout = TerminalRecovery.fromCode("MODEL_TIMEOUT");
        assertThat(timeout.category()).isEqualTo(TerminalRecovery.Category.RETRYABLE);

        TerminalRecovery providerUnavailable = TerminalRecovery.fromCode("MODEL_PROVIDER_UNAVAILABLE");
        assertThat(providerUnavailable.category()).isEqualTo(TerminalRecovery.Category.RETRYABLE);
        assertThat(providerUnavailable.action()).contains("/model");

        TerminalRecovery authFailed = TerminalRecovery.fromCode("MODEL_AUTHENTICATION_FAILED");
        assertThat(authFailed.category()).isEqualTo(TerminalRecovery.Category.USER_ACTION_REQUIRED);
        assertThat(authFailed.action()).contains("/connect", "/model");

        TerminalRecovery contextTooLong = TerminalRecovery.fromCode("MODEL_CONTEXT_TOO_LONG");
        assertThat(contextTooLong.category()).isEqualTo(TerminalRecovery.Category.USER_ACTION_REQUIRED);
        assertThat(contextTooLong.action()).contains("/compact");

        TerminalRecovery modelNotFound = TerminalRecovery.fromCode("MODEL_NOT_FOUND");
        assertThat(modelNotFound.category()).isEqualTo(TerminalRecovery.Category.USER_ACTION_REQUIRED);
        assertThat(modelNotFound.action()).contains("/model");
    }

    @Test
    void remoteFailuresGiveSpecificRecoveryActionsWithoutReplayingCommands() {
        assertThat(TerminalRecovery.fromCode("SESSION_NOT_FOUND").action()).contains("unavailable", "/resume");
        assertThat(TerminalRecovery.fromCode("SESSION_REVISION_CONFLICT").action())
                .contains("changed", "/resume");
        assertThat(TerminalRecovery.fromCode("INTERACTION_REVISION_CONFLICT").action())
                .contains("changed", "/approval reopen");
        assertThat(TerminalRecovery.fromCode("REVISION_CONFLICT").action()).contains("refresh", "review");
        assertThat(TerminalRecovery.fromCode("HOST_BUSY").action()).contains("capacity", "wait");
        for (String code : java.util.List.of("PAYLOAD_TOO_LARGE", "REMOTE_PAYLOAD_TOO_LARGE")) {
            assertThat(TerminalRecovery.fromCode(code).action()).contains("byte limit", "smaller history page");
        }
        assertThat(TerminalRecovery.fromCode("CLIENT_CONTEXT_REQUIRED").action())
                .contains("expired", "tui --connect", "Do not rerun");
        assertThat(TerminalRecovery.fromCode("REMOTE_OUTCOME_UNKNOWN").action())
                .contains("may still be executing", "authoritative outcome", "Do not blindly retry");
        assertThat(TerminalRecovery.fromCode("REMOTE_TRANSPORT_UNAVAILABLE").action())
                .contains("reconnect", "reopen");
        assertThat(TerminalRecovery.fromCode("REMOTE_PROTOCOL_INCOMPATIBLE").action())
                .contains("versions");
    }

    @Test
    void malformedOrUnboundedCodesCannotBecomeHostTextInTheUi() {
        for (String value : new String[] {null, "", "PRIVATE/path", "HOST_BUSY\nPRIVATE", "X".repeat(200)}) {
            TerminalRecovery recovery = TerminalRecovery.fromCode(value);
            assertThat(recovery.code()).isEqualTo("REMOTE_HOST_ERROR");
            assertThat(recovery.displayTitle()).doesNotContain("PRIVATE").hasSizeLessThan(128);
        }
    }

    @Test
    void unknownErrorCodeFallsBackSafely() {
        TerminalRecovery unknown = TerminalRecovery.fromCode("SOMETHING_UNEXPECTED");
        assertThat(unknown.category()).isEqualTo(TerminalRecovery.Category.USER_ACTION_REQUIRED);
        assertThat(unknown.code()).isEqualTo("SOMETHING_UNEXPECTED");
        assertThat(unknown.action())
                .isEqualTo(
                        "Review the current session state before deciding whether to retry; the editor draft is preserved.");
    }
}
