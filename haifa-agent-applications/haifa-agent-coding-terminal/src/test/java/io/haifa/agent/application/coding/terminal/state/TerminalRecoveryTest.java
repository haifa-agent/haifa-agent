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
    void knownErrorCodesPreserveActionablePresentation() {
        TerminalRecovery rateLimited = TerminalRecovery.fromCode("MODEL_RATE_LIMITED");
        assertThat(rateLimited.category()).isEqualTo(TerminalRecovery.Category.RETRYABLE);

        TerminalRecovery timeout = TerminalRecovery.fromCode("MODEL_TIMEOUT");
        assertThat(timeout.category()).isEqualTo(TerminalRecovery.Category.RETRYABLE);
    }

    @Test
    void unknownErrorCodeFallsBackSafely() {
        TerminalRecovery unknown = TerminalRecovery.fromCode("SOMETHING_UNEXPECTED");
        assertThat(unknown.category()).isEqualTo(TerminalRecovery.Category.USER_ACTION_REQUIRED);
        assertThat(unknown.code()).isEqualTo("SOMETHING_UNEXPECTED");
        assertThat(unknown.action()).isEqualTo("Review the request and retry; the editor draft is preserved.");
    }
}
