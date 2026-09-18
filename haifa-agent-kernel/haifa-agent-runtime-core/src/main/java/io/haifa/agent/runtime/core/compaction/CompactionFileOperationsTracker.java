package io.haifa.agent.runtime.core.compaction;

import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deterministically tracks file read and modification operations across compacted session history.
 * Injects structured &lt;modified-files&gt; and &lt;read-files&gt; XML tags into session summaries
 * to prevent LLM hallucination and ensure accurate downstream file context.
 */
public final class CompactionFileOperationsTracker {

    public static final int MAX_FILES_PER_TAG = 50;

    private static final Set<String> READ_TOOLS = Set.of("file_read", "workspace_file_read", "read_file", "view_file");

    private static final Set<String> MUTATION_TOOLS =
            Set.of("file_write", "write_to_file", "replace_file_content", "apply_patch", "patch");

    private static final List<String> PATH_ARGUMENT_KEYS =
            List.of("TargetFile", "targetFile", "path", "filePath", "target_file", "file", "fileName", "destination");

    public record FileOperations(Set<String> readFiles, Set<String> modifiedFiles) {
        public FileOperations {
            readFiles = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(readFiles, "readFiles")));
            modifiedFiles =
                    Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(modifiedFiles, "modifiedFiles")));
        }

        public static FileOperations empty() {
            return new FileOperations(Set.of(), Set.of());
        }

        public boolean isEmpty() {
            return readFiles.isEmpty() && modifiedFiles.isEmpty();
        }

        public String toXmlTags() {
            if (isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (!modifiedFiles.isEmpty()) {
                sb.append("<modified-files>\n");
                int count = 0;
                for (String path : modifiedFiles) {
                    if (count++ >= MAX_FILES_PER_TAG) {
                        sb.append("<!-- ... ")
                                .append(modifiedFiles.size() - MAX_FILES_PER_TAG)
                                .append(" more modified files omitted -->\n");
                        break;
                    }
                    sb.append(path).append("\n");
                }
                sb.append("</modified-files>");
            }
            if (!readFiles.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append("<read-files>\n");
                int count = 0;
                for (String path : readFiles) {
                    if (count++ >= MAX_FILES_PER_TAG) {
                        sb.append("<!-- ... ")
                                .append(readFiles.size() - MAX_FILES_PER_TAG)
                                .append(" more read files omitted -->\n");
                        break;
                    }
                    sb.append(path).append("\n");
                }
                sb.append("</read-files>");
            }
            return sb.toString();
        }
    }

    private CompactionFileOperationsTracker() {}

    /**
     * Extracts file read and mutation operations from an in-memory message list and tool resolver.
     */
    public static FileOperations track(List<AgentMessage> messages, Function<ToolCallId, ToolCall> resolver) {
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(resolver, "resolver must not be null");

        Set<String> readFiles = new TreeSet<>();
        Set<String> modifiedFiles = new TreeSet<>();

        for (AgentMessage message : messages) {
            for (var content : message.contents()) {
                if (content instanceof ToolCallPart callPart) {
                    ToolCall call = resolver.apply(callPart.toolCallId());
                    if (call != null) {
                        processToolCall(call, readFiles, modifiedFiles);
                    }
                }
            }
        }

        return new FileOperations(readFiles, modifiedFiles);
    }

    /**
     * Extracts file read and mutation operations for the given message IDs using the runtime state repository.
     */
    public static FileOperations track(List<AgentMessageId> messageIds, RuntimeStateRepository state) {
        Objects.requireNonNull(messageIds, "messageIds must not be null");
        Objects.requireNonNull(state, "state must not be null");

        Set<String> readFiles = new TreeSet<>();
        Set<String> modifiedFiles = new TreeSet<>();
        Map<AgentRunId, Map<ToolCallId, ToolCall>> toolCallsByRun = new HashMap<>();

        for (AgentMessageId messageId : messageIds) {
            Optional<AgentMessage> messageOpt = state.message(messageId);
            if (messageOpt.isEmpty()) {
                continue;
            }
            AgentMessage message = messageOpt.get();
            AgentRunId runId = message.runId().orElse(null);
            for (var content : message.contents()) {
                if (content instanceof ToolCallPart callPart) {
                    ToolCall call = null;
                    if (runId != null) {
                        Map<ToolCallId, ToolCall> runCalls =
                                toolCallsByRun.computeIfAbsent(runId, rId -> state.toolCalls(rId).stream()
                                        .collect(Collectors.toMap(ToolCall::id, Function.identity(), (a, b) -> a)));
                        call = runCalls.get(callPart.toolCallId());
                    }
                    if (call != null) {
                        processToolCall(call, readFiles, modifiedFiles);
                    }
                }
            }
        }

        return new FileOperations(readFiles, modifiedFiles);
    }

    /**
     * Appends deterministic &lt;modified-files&gt; and &lt;read-files&gt; tags to summary markdown.
     */
    public static String appendToFileOperations(String summaryMarkdown, FileOperations operations) {
        if (operations == null || operations.isEmpty()) {
            return summaryMarkdown;
        }
        String tags = operations.toXmlTags();
        if (summaryMarkdown == null || summaryMarkdown.isBlank()) {
            return tags;
        }
        return summaryMarkdown.trim() + "\n\n" + tags;
    }

    private static void processToolCall(ToolCall call, Set<String> readFiles, Set<String> modifiedFiles) {
        if (call == null || call.result().isEmpty() || !call.result().get().successful()) {
            return;
        }
        String toolName = call.toolName().toLowerCase(Locale.ROOT);
        String path = extractFilePath(call.arguments().values());
        if (path == null || path.isBlank()) {
            return;
        }

        if (matchesTool(toolName, MUTATION_TOOLS)) {
            modifiedFiles.add(path.trim());
        } else if (matchesTool(toolName, READ_TOOLS)) {
            readFiles.add(path.trim());
        }
    }

    private static boolean matchesTool(String toolName, Set<String> targetTools) {
        if (targetTools.contains(toolName)) {
            return true;
        }
        for (String target : targetTools) {
            if (toolName.endsWith("_" + target)
                    || toolName.endsWith(":" + target)
                    || toolName.endsWith("/" + target)
                    || toolName.endsWith("." + target)) {
                return true;
            }
        }
        return false;
    }

    private static String extractFilePath(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        for (String key : PATH_ARGUMENT_KEYS) {
            Object val = arguments.get(key);
            if (val instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
