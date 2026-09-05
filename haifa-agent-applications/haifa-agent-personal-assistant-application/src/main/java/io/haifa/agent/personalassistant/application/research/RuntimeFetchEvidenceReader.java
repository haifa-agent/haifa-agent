package io.haifa.agent.personalassistant.application.research;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.web.DefaultWebUrlPolicy;
import io.haifa.agent.web.WebUrlPolicy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Runtime Core-backed implementation of {@link ResearchFetchEvidenceReader}.
 *
 * <p>Extracts completed web_fetch tool executions from {@link RuntimePersistencePorts#state()}
 * without issuing direct database queries or exposing raw scraped content.
 */
public final class RuntimeFetchEvidenceReader implements ResearchFetchEvidenceReader {
    private static final String EMPTY_SHA256 =
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final Pattern BARE_SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern CANONICAL_SHA256 = Pattern.compile("^sha256:[a-f0-9]{64}$");

    private final RuntimePersistencePorts runtimePersistence;
    private final WebUrlPolicy urlPolicy;

    public RuntimeFetchEvidenceReader(RuntimePersistencePorts runtimePersistence) {
        this(runtimePersistence, new DefaultWebUrlPolicy());
    }

    public RuntimeFetchEvidenceReader(RuntimePersistencePorts runtimePersistence, WebUrlPolicy urlPolicy) {
        this.runtimePersistence = Objects.requireNonNull(runtimePersistence, "runtimePersistence must not be null");
        this.urlPolicy = Objects.requireNonNull(urlPolicy, "urlPolicy must not be null");
    }

    @Override
    public List<ResearchFetchEvidence> findCompletedFetches(String runId) {
        if (runId == null || runId.isBlank()) return List.of();
        List<ToolCall> calls = runtimePersistence.state().toolCalls(new AgentRunId(runId));
        if (calls == null || calls.isEmpty()) return List.of();

        List<ResearchFetchEvidence> completedFetches = new ArrayList<>();
        for (ToolCall call : calls) {
            if (!isWebFetchTool(call)) continue;
            if (call.status() != ToolCallStatus.COMPLETED) continue;

            ToolResult result = call.result().orElse(null);
            Map<String, Object> data = result != null ? result.structuredData() : Map.of();
            String rawRequestedUrl = extractUrl(call, data, "requestedUrl");
            String rawFinalUrl = extractUrl(call, data, "finalUrl");
            if (rawRequestedUrl.isBlank() && rawFinalUrl.isBlank()) continue;
            if (rawFinalUrl.isBlank()) rawFinalUrl = rawRequestedUrl;
            if (rawRequestedUrl.isBlank()) rawRequestedUrl = rawFinalUrl;

            String canonicalRequestedUrl = canonicalize(rawRequestedUrl);
            String canonicalFinalUrl = canonicalize(rawFinalUrl);
            if (canonicalRequestedUrl.isBlank() || canonicalFinalUrl.isBlank()) continue;

            boolean sourceAvailable = Boolean.TRUE.equals(data.get("sourceAvailable"));
            boolean resultSuccessful = result != null && result.successful();
            String content = data.get("content") instanceof String value ? value : "";
            String digest = canonicalDigest(data.get("contentSha256"));
            boolean successful = resultSuccessful && sourceAvailable && !EMPTY_SHA256.equals(digest);
            boolean truncated = Boolean.TRUE.equals(data.get("truncated"));
            Instant completedAt = call.completedAt().or(call::startedAt).orElseGet(call::requestedAt);

            completedFetches.add(new ResearchFetchEvidence(
                    call.id().value(),
                    canonicalRequestedUrl,
                    canonicalFinalUrl,
                    successful,
                    sourceAvailable,
                    completedAt,
                    digest,
                    content.getBytes(StandardCharsets.UTF_8).length,
                    truncated));
        }
        return List.copyOf(completedFetches);
    }

    private static boolean isWebFetchTool(ToolCall call) {
        String name = call.toolName();
        return "web_fetch".equalsIgnoreCase(name) || "web.fetch".equalsIgnoreCase(name);
    }

    private static String extractUrl(ToolCall call, Map<String, Object> data, String key) {
        if (data.get(key) instanceof String url && !url.isBlank()) {
            return url.trim();
        }
        Map<String, Object> arguments = call.arguments().values();
        if (arguments.get("url") instanceof String url && !url.isBlank()) {
            return url.trim();
        }
        return "";
    }

    private String canonicalize(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl).normalize();
            var decision = urlPolicy.evaluate(uri);
            if (!decision.allowed()) return "";
            return decision.normalizedUrl().toASCIIString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String canonicalDigest(Object rawDigest) {
        if (!(rawDigest instanceof String value)) return EMPTY_SHA256;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (BARE_SHA256.matcher(normalized).matches()) return "sha256:" + normalized;
        if (CANONICAL_SHA256.matcher(normalized).matches()) return normalized;
        return EMPTY_SHA256;
    }
}
