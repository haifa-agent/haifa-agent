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
            "aliyun-bailian", "阿里云百炼",
            "zhipu", "智谱 AI",
            "kimi", "Kimi",
            "siliconflow", "SiliconFlow",
            "tokenrhythm", "TokenRhythm");

    private static final Map<String, String> HELP_URLS = Map.of(
            "deepseek", "https://platform.deepseek.com/api_keys",
            "aliyun-bailian", "https://bailian.console.aliyun.com/",
            "zhipu", "https://open.bigmodel.cn/usercenter/apikeys",
            "kimi", "https://platform.moonshot.cn/console/api-keys",
            "siliconflow", "https://cloud.siliconflow.cn/account/ak",
            "openai-codex", "https://chatgpt.com/",
            "google-antigravity", "https://antigravity.google/");

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
                provider.category() == CodingAuthenticationProviderView.Category.SUBSCRIPTION ? "订阅登录" : "API Key");
    }

    private static String providerDescription(CodingAuthenticationProviderView provider) {
        String type =
                provider.category() == CodingAuthenticationProviderView.Category.SUBSCRIPTION ? "订阅登录" : "API Key";
        if (provider.connected()) {
            String account = provider.accountLabel()
                    .map(AuthenticationProviderPresentation::maskAccount)
                    .orElse("");
            return type + (account.isBlank() ? " · 已连接" : " · " + account);
        }
        return type
                + (provider.category() == CodingAuthenticationProviderView.Category.SUBSCRIPTION
                        ? " · 无需 API Key"
                        : " · 按量计费");
    }

    private static String providerBadge(CodingAuthenticationProviderView provider) {
        if (provider.current()) {
            return "当前";
        }
        if (provider.connected()) {
            return "已连接";
        }
        return provider.recommended() ? "推荐" : "";
    }

    static String methodLabel(CodingAuthenticationMethodView method) {
        return switch (method.id()) {
            case "browser" -> "浏览器登录（推荐）";
            case "device" -> "设备码登录";
            case "api_key" -> "填写 API Key";
            default -> method.id();
        };
    }

    static String methodDescription(CodingAuthenticationMethodView method) {
        return switch (method.id()) {
            case "browser" -> "自动打开浏览器，完成后自动继续";
            case "device" -> "适合远程终端或浏览器无法打开时";
            case "api_key" -> "按量计费，需要提供商 API Key";
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
