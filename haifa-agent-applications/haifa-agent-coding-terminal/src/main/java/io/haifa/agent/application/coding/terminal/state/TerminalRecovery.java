package io.haifa.agent.application.coding.terminal.state;

import java.util.Map;
import java.util.Objects;

/** Actionable presentation for stable product/terminal failure codes. */
public record TerminalRecovery(Category category, String code, String action) {
    private static final Map<String, TerminalRecovery> KNOWN = Map.ofEntries(
            entry(Category.RETRYABLE, "EVENT_OUT_OF_ORDER", "Reconcile the session, then retry the last action."),
            entry(Category.RETRYABLE, "EVENT_RUN_MISMATCH", "Reconcile the session, then retry the last action."),
            entry(Category.RETRYABLE, "ACTIVE_RUN_SETTLED", "The session changed while submitting; retry the message."),
            entry(
                    Category.RETRYABLE,
                    "ACTIVE_RUN_MISMATCH",
                    "The session changed while submitting; retry the message."),
            entry(Category.USER_ACTION_REQUIRED, "SESSION_REQUIRED", "Create or resume a session, then retry."),
            entry(Category.USER_ACTION_REQUIRED, "SESSION_NOT_FOUND", "Use /resume to choose an available session."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "RESTORABLE_QUEUE_EMPTY",
                    "There are no queued follow-ups to restore."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "CAPABILITY_NOT_IMPLEMENTED",
                    "Use a supported command or capability."),
            entry(Category.USER_ACTION_REQUIRED, "COMMAND_UNKNOWN", "Use /commands to choose a supported command."),
            entry(
                    Category.TERMINAL_CAPABILITY,
                    "MODIFIED_ENTER_UNAVAILABLE",
                    "Use Ctrl+J for a newline and Alt/Option+Enter for follow-up."),
            entry(
                    Category.TERMINAL_CAPABILITY,
                    "WINDOWS_TERMINAL_MODIFIED_ENTER_REMAP",
                    "Windows Terminal: use Ctrl+J for newline. Shift+Enter and Alt+Enter may need custom key bindings."),
            entry(
                    Category.TERMINAL_CAPABILITY,
                    "WEZTERM_OPTION_ENTER_REMAP",
                    "WezTerm: use Ctrl+J for newline. Option+Enter may need a custom key binding."),
            entry(
                    Category.TERMINAL_CAPABILITY,
                    "ALACRITTY_OPTION_ENTER_REMAP",
                    "Alacritty: use Ctrl+J for newline. Option+Enter may need a custom key binding."),
            entry(
                    Category.TERMINAL_CAPABILITY,
                    "APPLE_TERMINAL_MODIFIED_ENTER_LIMITED",
                    "Use Ctrl+J for newline; modified Enter may not work over SSH."),
            entry(Category.TERMINAL_CAPABILITY, "TERMINAL_TOO_SMALL", "Resize the terminal to at least 60x16."),
            entry(Category.INTERRUPTED, "RUN_INTERRUPTED", "Edit the preserved draft, then submit again."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "RUN_BUDGET_EXCEEDED",
                    "Start a new run with a smaller request or an explicitly larger budget."),
            entry(Category.RETRYABLE, "MODEL_RATE_LIMITED", "Wait for backoff, then retry the request."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "MODEL_AUTHENTICATION_REQUIRED",
                    "Not signed in or credentials unavailable; run /connect to connect or sign in again, or run /model to switch models."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_PROVIDER_LIST_EMPTY",
                    "No model provider is available; check the model configuration and retry."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_PROVIDER_INVALID",
                    "This model provider is unavailable; run /connect to choose another."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_ACCOUNT_NOT_SELECTED",
                    "No model account is selected; run /account to choose one."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_METHOD_UNSUPPORTED",
                    "This sign-in method is not supported here; run /connect to choose another method."),
            entry(
                    Category.INTERRUPTED,
                    "AUTH_CANCELLED",
                    "Sign-in cancelled; your draft is preserved. Run /connect to retry."),
            entry(
                    Category.RETRYABLE,
                    "AUTH_CALLBACK_TIMEOUT",
                    "Browser authorization timed out; retry or use device code sign-in."),
            entry(
                    Category.RETRYABLE,
                    "AUTH_CALLBACK_UNAVAILABLE",
                    "Could not start the local callback port; check port usage or the firewall, or use device code sign-in."),
            entry(Category.RETRYABLE, "AUTH_CALLBACK_FAILED", "Browser callback did not complete; retry sign-in."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_STORE_FAILED",
                    "Credentials could not be written to the system credential store; check credential manager permissions and retry."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_LOGIN_IN_PROGRESS",
                    "A sign-in is already in progress; wait for it to finish, or press Esc to cancel and retry."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_REAUTH_REQUIRED",
                    "Sign-in has expired; run /connect to sign in again."),
            entry(
                    Category.RETRYABLE,
                    "AUTH_LOGIN_SERVICE_UNAVAILABLE",
                    "The sign-in service is temporarily unavailable; check your network and retry."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_DEVICE_CODE_EXPIRED",
                    "Device code expired; request a new device code."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_DEVICE_CODE_DENIED",
                    "Device code authorization was denied; retry or use browser sign-in."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_SECRET_INVALID",
                    "The API key is empty or malformed; return to the form and enter it again."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_WORKSPACE_ID_INVALID",
                    "Invalid workspace ID; use lowercase letters, digits, or hyphens."),
            entry(Category.USER_ACTION_REQUIRED, "AUTH_REGION_INVALID", "Invalid region; choose the region again."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_SECURE_INPUT_UNAVAILABLE",
                    "Credentials cannot be entered securely in this environment; check the terminal or use browser sign-in."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_API_KEY_UNAVAILABLE",
                    "This provider has no writable API key credential; check the model configuration."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_LOGIN_METHOD_UNAVAILABLE",
                    "Sign-in methods are not enabled for this provider; check the local compatibility configuration."),
            entry(Category.USER_ACTION_REQUIRED, "MODEL_PAYMENT_REQUIRED", "请检查 Provider 账户余额、套餐、模型授权或账单状态后重试"),
            entry(Category.RETRYABLE, "MODEL_TIMEOUT", "Retry the request after checking provider availability."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "TOOL_OUTCOME_UNKNOWN",
                    "Inspect the tool outcome before retrying; do not blindly replay the action."),
            entry(
                    Category.RETRYABLE,
                    "TOOL_RESULT_PERSISTENCE_FAILED",
                    "Check the runtime store, then resume so the known tool result can be persisted."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "WORKSPACE_MANIFEST_UNAVAILABLE",
                    "Fix or ignore the inaccessible workspace path, then retry the command."),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "PASTE_PLACEHOLDER_ATOMIC",
                    "A pasted block is kept whole; delete it from its edge in one step and retry."),
            entry(
                    Category.TERMINAL_FAILURE,
                    "TERMINAL_FAILURE",
                    "Restart the terminal; the session remains recoverable."));

    public TerminalRecovery {
        category = Objects.requireNonNull(category, "category must not be null");
        code = require(code, "code");
        action = require(action, "action");
    }

    public static TerminalRecovery fromCode(String code) {
        String checked = require(code, "code");
        return KNOWN.getOrDefault(
                checked,
                new TerminalRecovery(
                        Category.USER_ACTION_REQUIRED,
                        checked,
                        "Review the request and retry; the editor draft is preserved."));
    }

    /** User-facing title; stable capability codes remain available through {@link #code()}. */
    public String displayTitle() {
        if (category == Category.TERMINAL_CAPABILITY) return category.label();
        return category.label() + " · " + code;
    }

    private static Map.Entry<String, TerminalRecovery> entry(Category category, String code, String action) {
        return Map.entry(code, new TerminalRecovery(category, code, action));
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    public enum Category {
        RETRYABLE("Retryable"),
        USER_ACTION_REQUIRED("User action required"),
        INTERRUPTED("Interrupted"),
        TERMINAL_CAPABILITY("Terminal capability"),
        TERMINAL_FAILURE("Terminal failure");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
