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
        StringBuilder label = new StringBuilder(providerName(provider));
        label.append(provider.category() == CodingAuthenticationProviderView.Category.SUBSCRIPTION
                ? " · 订阅"
                : " · API Key");
        if (provider.recommended()) {
            label.append(" · 推荐");
        }
        if (provider.connected()) {
            label.append(" · 已连接");
        }
        if (provider.current()) {
            label.append(" · 当前");
        }
        return label.toString();
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
}
