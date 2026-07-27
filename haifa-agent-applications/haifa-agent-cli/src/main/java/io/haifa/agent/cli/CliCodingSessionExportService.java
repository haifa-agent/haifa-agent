package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.CodingSessionExportResult;
import io.haifa.agent.application.project.product.coding.CodingSessionExportService;
import io.haifa.agent.application.project.product.coding.CodingSessionService;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.credential.api.SecretRedactor;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes a redacted, non-restorable JSONL projection to a new workspace-relative file. */
final class CliCodingSessionExportService implements CodingSessionExportService {
    private static final String SCHEMA_VERSION = "haifa.coding-session-export/1";
    private static final int MAX_PREVIEW = 512;
    private final Path root;
    private final CodingSessionService sessions;
    private final RuntimeStateRepository state;
    private final SecretRedactor redactor;
    private final ObjectMapper json = new ObjectMapper();

    CliCodingSessionExportService(
            Path workspaceRoot, CodingSessionService sessions, RuntimeStateRepository state, SecretRedactor redactor) {
        try {
            this.root = workspaceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalArgumentException("workspace root is unavailable", exception);
        }
        this.sessions = sessions;
        this.state = state;
        this.redactor = redactor;
    }

    @Override
    public CodingSessionExportResult export(AgentSessionId sessionId, String logicalDestination) {
        sessions.openSession(sessionId);
        Path destination = resolveDestination(root, logicalDestination);
        List<io.haifa.agent.core.message.AgentMessage> messages =
                state.messagesAfter(sessionId, MessageCursor.BEFORE_FIRST, Integer.MAX_VALUE);
        List<String> lines = new ArrayList<>();
        lines.add(json(Map.of(
                "schemaVersion",
                SCHEMA_VERSION,
                "recordType",
                "session",
                "sessionId",
                sessionId.value(),
                "messageCount",
                messages.size())));
        for (var message : messages) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schemaVersion", SCHEMA_VERSION);
            row.put("recordType", "message");
            row.put("messageId", message.id().value());
            row.put("sessionId", message.sessionId().value());
            message.runId().ifPresent(value -> row.put("runId", value.value()));
            message.parentMessageId().ifPresent(value -> row.put("parentMessageId", value.value()));
            row.put("sequence", message.sequence());
            row.put("role", message.role().name());
            row.put("status", message.status().name());
            row.put("visibility", message.visibility().name());
            row.put("createdAt", message.createdAt().toString());
            row.put("content", safeContent(message.contents(), message.visibility() == MessageVisibility.USER_VISIBLE));
            lines.add(json(row));
        }
        try {
            Files.writeString(
                    destination,
                    String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new IllegalStateException("SESSION_EXPORT_FAILED", exception);
        }
        return new CodingSessionExportResult(
                root.relativize(destination).toString().replace('\\', '/'), messages.size(), SCHEMA_VERSION);
    }

    static Path resolveDestination(Path root, String logical) {
        if (logical == null || logical.isBlank()) {
            throw new IllegalArgumentException("EXPORT_PATH_REQUIRED");
        }
        Path relative = Path.of(logical);
        if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
            throw new IllegalArgumentException("EXPORT_PATH_OUTSIDE_WORKSPACE");
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("EXPORT_DESTINATION_UNAVAILABLE");
        }
        Path parent = target.getParent();
        try {
            if (parent == null
                    || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(parent)
                    || !parent.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(root)) {
                throw new IllegalArgumentException("EXPORT_PARENT_UNTRUSTED");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("EXPORT_PARENT_UNTRUSTED", exception);
        }
        return target;
    }

    private List<Map<String, Object>> safeContent(
            List<io.haifa.agent.core.content.ContentPart> parts, boolean userVisible) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (var part : parts) {
            if (part instanceof TextPart text) {
                values.add(
                        userVisible
                                ? Map.of("type", "text-preview", "preview", bounded(text.text(), redactor))
                                : Map.of("type", "text-redacted"));
            } else if (part instanceof ToolCallPart call) {
                values.add(Map.of(
                        "type", "tool-call-ref",
                        "toolCallId", call.toolCallId().value(),
                        "toolName", call.toolName()));
            } else if (part instanceof ToolResultPart result) {
                values.add(Map.of(
                        "type", "tool-result-ref",
                        "toolCallId", result.toolCallId().value(),
                        "summary", bounded(result.summary(), redactor)));
            } else {
                values.add(Map.of("type", part.getClass().getSimpleName()));
            }
        }
        return List.copyOf(values);
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("SESSION_EXPORT_FAILED", exception);
        }
    }

    static String bounded(String value, SecretRedactor redactor) {
        String safe = redactor.redact(value)
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .strip();
        return safe.length() <= MAX_PREVIEW ? safe : safe.substring(0, MAX_PREVIEW) + "…";
    }
}
