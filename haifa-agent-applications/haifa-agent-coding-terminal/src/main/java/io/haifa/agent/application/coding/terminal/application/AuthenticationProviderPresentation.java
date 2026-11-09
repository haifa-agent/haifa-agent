package io.haifa.agent.application.coding.terminal.application;

import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationMethodView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProviderView;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Product copy for the connection center; provider ids remain the stable routing key. */
final class AuthenticationProviderPresentation {
    private static final Map<String, String> NAMES = Map.of(
            "openai-codex", "ChatGPT (Codex)",
            "google-antigravity", "Antigravity",
            "deepseek", "DeepSeek",
            "aliyun-bailian", "Aliyun Bailian",
            "zhipu", "Zhipu AI",
            "kimi", "Kimi",
            "siliconflow", "SiliconFlow",
            "tokenrhythm", "TokenRhythm",
            "opencode-go", "OpenCode Go");

    private static final Map<String, String> HELP_URLS = Map.of(
            "deepseek", "https://platform.deepseek.com/api_keys",
            "aliyun-bailian", "https://bailian.console.aliyun.com/",
            "zhipu", "https://open.bigmodel.cn/usercenter/apikeys",
            "kimi", "https://platform.moonshot.cn/console/api-keys",
            "siliconflow", "https://cloud.siliconflow.cn/account/ak",
            "openai-codex", "https://chatgpt.com/",
            "google-antigravity", "https://antigravity.google/",
            "opencode-go", "https://opencode.ai/v2/docs/console/go/");

    private AuthenticationProviderPresentation() {}

    static String providerName(CodingAuthenticationProviderView provider) {
        return NAMES.getOrDefault(provider.providerId().toLowerCase(Locale.ROOT), provider.displayName());
    }

    static String providerName(String providerId, String fallback) {
        return NAMES.getOrDefault(providerId.toLowerCase(Locale.ROOT), fallback);
    }

    static String providerOption(CodingAuthenticationProviderView provider) {
        return providerName(provider) + " · " + providerDescription(provider);
    }

    static io.haifa.agent.application.coding.terminal.state.TerminalSelectorDetail providerDetail(
            CodingAuthenticationProviderView provider) {
        return new io.haifa.agent.application.coding.terminal.state.TerminalSelectorDetail(
                providerDescription(provider),
                providerBadge(provider),
                provider.category() == CodingAuthenticationProviderView.Category.SUBSCRIPTION
                        ? "Subscription"
                        : "API Key");
    }

    private static String providerDescription(CodingAuthenticationProviderView provider) {
        String type = provider.category() == CodingAuthenticationProviderView.Category.SUBSCRIPTION
                ? "Subscription"
                : "API Key";
        if (provider.connected()) {
            String account = provider.accountLabel()
                    .map(AuthenticationProviderPresentation::maskAccount)
                    .orElse("");
            return type + (account.isBlank() ? " · Connected" : " · " + account);
        }
        return type
                + (provider.category() == CodingAuthenticationProviderView.Category.SUBSCRIPTION
                        ? " · No API key required"
                        : " · Pay as you go");
    }

    private static String providerBadge(CodingAuthenticationProviderView provider) {
        if (provider.current()) {
            return "Current";
        }
        if (provider.connected()) {
            return "Connected";
        }
        return provider.recommended() ? "Recommended" : "";
    }

    static String methodLabel(CodingAuthenticationMethodView method) {
        return switch (method.id()) {
            case "browser" -> "Browser sign-in (recommended)";
            case "device" -> "Device code sign-in";
            case "api_key" -> "Enter API key";
            default -> method.id();
        };
    }

    static String methodDescription(CodingAuthenticationMethodView method) {
        return switch (method.id()) {
            case "browser" -> "Opens the browser and continues automatically";
            case "device" -> "Best for remote terminals or when a browser cannot open";
            case "api_key" -> "Pay as you go; requires a provider API key";
            default -> "";
        };
    }

    static Optional<String> helpUrl(String providerId) {
        return Optional.ofNullable(HELP_URLS.get(providerId.toLowerCase(Locale.ROOT)));
    }

    static String maskAccount(String value) {
        String label = value == null ? "" : value.strip();
        if (label.isEmpty()) {
            return "";
        }
        int at = label.indexOf('@');
        if (at > 0) {
            return label.substring(0, 1) + "***" + label.substring(at);
        }
        if (label.length() <= 4) {
            return "***";
        }
        return label.substring(0, 1) + "***" + label.substring(label.length() - 2);
    }
}
