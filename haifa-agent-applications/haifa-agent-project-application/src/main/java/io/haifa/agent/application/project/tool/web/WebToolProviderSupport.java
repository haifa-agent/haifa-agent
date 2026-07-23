package io.haifa.agent.application.project.tool.web;

import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class WebToolProviderSupport {
    private WebToolProviderSupport() {}

    static WebProviderInvocationContext context(ToolInvocationRequest request) {
        return new WebProviderInvocationContext(
                request.deadline(),
                request.cancellation()::isCancellationRequested,
                request.credentialLeases(),
                new io.haifa.agent.application.project.tool.web.WebInvocationObserver() {
                    @Override
                    public void dispatched() {
                        request.observer().dispatched();
                    }

                    @Override
                    public void acknowledged() {
                        request.observer().acknowledged();
                    }
                });
    }

    static WebSearchRequest searchRequest(Map<String, Object> values) {
        return new WebSearchRequest(
                text(values, "query"),
                integer(values, "maxResults", 5),
                optionalText(values, "language"),
                optionalText(values, "country"),
                optionalEnum(values, "freshness", io.haifa.agent.application.project.tool.web.WebFreshness.class),
                strings(values, "includeDomains"),
                strings(values, "excludeDomains"),
                optionalEnum(values, "safeSearch", io.haifa.agent.application.project.tool.web.WebSafeSearch.class));
    }

    static void requireSupported(WebSearchRequest request, Set<WebSearchOption> supported) {
        requireOption(request.language().isPresent(), WebSearchOption.LANGUAGE, supported);
        requireOption(request.country().isPresent(), WebSearchOption.COUNTRY, supported);
        requireOption(request.freshness().isPresent(), WebSearchOption.FRESHNESS, supported);
        requireOption(!request.includeDomains().isEmpty(), WebSearchOption.INCLUDE_DOMAINS, supported);
        requireOption(!request.excludeDomains().isEmpty(), WebSearchOption.EXCLUDE_DOMAINS, supported);
        requireOption(request.safeSearch().isPresent(), WebSearchOption.SAFE_SEARCH, supported);
    }

    static URI uri(Map<String, Object> values, String key) {
        try {
            return URI.create(text(values, key));
        } catch (IllegalArgumentException exception) {
            throw new ToolInvocationException(
                    WebFailureCode.WEB_INVALID_REQUEST.name(), ToolDispatchState.NOT_DISPATCHED, "web URL is invalid");
        }
    }

    static <T extends Enum<T>> T enumValue(Map<String, Object> values, String key, Class<T> type, T fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("web argument " + key + " is invalid");
        }
    }

    static ToolInvocationException map(WebProviderException exception) {
        return new ToolInvocationException(
                exception.failureCode().name(), dispatch(exception.dispatchState()), exception.getMessage(), exception);
    }

    static ToolInvocationException invalid(String message) {
        return new ToolInvocationException(
                WebFailureCode.WEB_INVALID_REQUEST.name(), ToolDispatchState.NOT_DISPATCHED, message);
    }

    private static void requireOption(boolean requested, WebSearchOption option, Set<WebSearchOption> supported) {
        if (requested && !supported.contains(option)) {
            throw new ToolInvocationException(
                    WebFailureCode.WEB_UNSUPPORTED_OPTION.name(),
                    ToolDispatchState.NOT_DISPATCHED,
                    "configured web search provider does not support option "
                            + option.name().toLowerCase(Locale.ROOT));
        }
    }

    private static ToolDispatchState dispatch(WebDispatchState state) {
        return ToolDispatchState.valueOf(state.name());
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw invalid("web argument " + key + " is required");
        return text.trim();
    }

    private static Optional<String> optionalText(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return Optional.empty();
        if (!(value instanceof String text) || text.isBlank()) throw invalid("web argument " + key + " is invalid");
        return Optional.of(text.trim());
    }

    private static int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw invalid("web argument " + key + " is invalid");
        return number.intValue();
    }

    private static List<String> strings(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) {
            throw invalid("web argument " + key + " is invalid");
        }
        return list.stream().map(String.class::cast).toList();
    }

    private static <T extends Enum<T>> Optional<T> optionalEnum(Map<String, Object> values, String key, Class<T> type) {
        Object value = values.get(key);
        if (value == null) return Optional.empty();
        try {
            return Optional.of(Enum.valueOf(type, String.valueOf(value).trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            throw invalid("web argument " + key + " is invalid");
        }
    }
}
