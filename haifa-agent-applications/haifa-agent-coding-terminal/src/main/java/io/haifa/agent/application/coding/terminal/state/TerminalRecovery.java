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
                    "未登录/凭据不可用；运行 /connect 连接或重新登录，或运行 /model 切换可用模型。"),
            entry(Category.USER_ACTION_REQUIRED, "AUTH_PROVIDER_LIST_EMPTY", "没有可用模型提供商；检查模型配置后重试。"),
            entry(Category.USER_ACTION_REQUIRED, "AUTH_PROVIDER_INVALID", "该模型提供商不可用；运行 /connect 重新选择。"),
            entry(Category.USER_ACTION_REQUIRED, "AUTH_ACCOUNT_NOT_SELECTED", "未选择模型账号；运行 /account 重新选择。"),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_METHOD_UNSUPPORTED",
                    "当前环境不支持该登录方式；运行 /connect 选择其他方式。"),
            entry(Category.INTERRUPTED, "AUTH_CANCELLED", "登录已取消；草稿已保留，可运行 /connect 重试。"),
            entry(
                    Category.RETRYABLE,
                    "AUTH_CALLBACK_TIMEOUT",
                    "浏览器授权等待超时；重试，或改用设备码登录。"),
            entry(
                    Category.RETRYABLE,
                    "AUTH_CALLBACK_UNAVAILABLE",
                    "无法启动本机回调端口；检查端口占用或防火墙，或改用设备码登录。"),
            entry(Category.RETRYABLE, "AUTH_CALLBACK_FAILED", "浏览器回调未能完成；重试登录。"),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_STORE_FAILED",
                    "凭据无法写入系统凭据库；检查系统凭据管理器权限后重试。"),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_LOGIN_IN_PROGRESS",
                    "已有登录正在进行；等待完成，或按 Esc 取消后重试。"),
            entry(Category.USER_ACTION_REQUIRED, "AUTH_REAUTH_REQUIRED", "登录已失效；运行 /connect 重新登录。"),
            entry(
                    Category.RETRYABLE,
                    "AUTH_LOGIN_SERVICE_UNAVAILABLE",
                    "登录服务暂不可用；检查网络后重试。"),
            entry(Category.USER_ACTION_REQUIRED, "AUTH_DEVICE_CODE_EXPIRED", "设备码已过期；重新获取设备码。"),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_DEVICE_CODE_DENIED",
                    "设备码授权被拒绝；重试，或改用浏览器登录。"),
            entry(Category.USER_ACTION_REQUIRED, "AUTH_SECRET_INVALID", "API Key 为空或格式不正确；返回表单重新输入。"),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_WORKSPACE_ID_INVALID",
                    "Workspace ID 格式不正确；使用小写字母、数字或连字符。"),
            entry(Category.USER_ACTION_REQUIRED, "AUTH_REGION_INVALID", "地域格式不正确；重新选择地域。"),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_SECURE_INPUT_UNAVAILABLE",
                    "当前环境无法安全输入凭据；检查终端，或改用浏览器登录。"),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_API_KEY_UNAVAILABLE",
                    "该提供商未配置可写的 API Key 凭据；检查模型配置。"),
            entry(
                    Category.USER_ACTION_REQUIRED,
                    "AUTH_LOGIN_METHOD_UNAVAILABLE",
                    "该提供商的登录方式未启用；检查本地兼容配置。"),
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
