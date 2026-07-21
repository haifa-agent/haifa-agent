package io.haifa.agent.project.index;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.project.changeset.FileChangeSet;
import io.haifa.agent.project.changeset.FileChangeSetStatus;
import io.haifa.agent.project.filesystem.FileListRequest;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.filesystem.ReadOptions;
import io.haifa.agent.project.filesystem.WorkspaceFileException;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.index.document.DocumentNode;
import io.haifa.agent.project.index.document.DocumentNodeKind;
import io.haifa.agent.project.index.file.FileIndexEntry;
import io.haifa.agent.project.index.file.FileIndexQuery;
import io.haifa.agent.project.index.file.FileIndexResult;
import io.haifa.agent.project.index.symbol.SymbolKind;
import io.haifa.agent.project.index.symbol.SymbolRecord;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Rebuildable, generation-switched file, Java symbol and Markdown document index. */
public final class ProjectIndexService {
    private static final int MAX_SOURCE_BYTES = 512 * 1024;
    private static final Set<String> IGNORED_SEGMENTS =
            Set.of(".git", "target", "build", "node_modules", ".haifa-quarantine", ".haifa-temp");
    private static final Pattern PACKAGE =
            Pattern.compile("^\\s*package\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*)\\s*;");
    private static final Pattern TYPE = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(?:static\\s+|final\\s+|sealed\\s+|non-sealed\\s+|abstract\\s+)*(class|interface|enum|record)\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)");
    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(?:static\\s+|final\\s+|abstract\\s+|synchronized\\s+|native\\s+|default\\s+)*(?:[\\p{L}\\p{N}_$<>,.?\\[\\] ]+\\s+)?([\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[^\\{]+)?[\\{;]");

    private final WorkspaceStore workspaces;
    private final WorkspaceFileService files;
    private final TimeProvider time;
    private final ConcurrentHashMap<WorkspaceId, AtomicReference<Snapshot>> snapshots = new ConcurrentHashMap<>();

    public ProjectIndexService(WorkspaceStore workspaces, WorkspaceFileService files, TimeProvider time) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    public IndexGeneration rebuild(WorkspaceId workspaceId) {
        var workspace =
                workspaces.find(workspaceId).orElseThrow(() -> new IllegalArgumentException("workspace not found"));
        AtomicReference<Snapshot> reference =
                snapshots.computeIfAbsent(workspaceId, ignored -> new AtomicReference<>());
        synchronized (reference) {
            IndexGeneration generation = reference.get() == null
                    ? new IndexGeneration(1)
                    : reference.get().generation().next();
            Map<ProjectPath, FileIndexEntry> entries = new HashMap<>();
            Map<ProjectPath, List<SymbolRecord>> symbols = new HashMap<>();
            Map<ProjectPath, List<DocumentNode>> documents = new HashMap<>();
            var directories = new ArrayDeque<WorkspacePath>();
            directories.add(WorkspacePath.root(workspaceId));
            while (!directories.isEmpty()) {
                WorkspacePath directory = directories.removeFirst();
                int offset = 0;
                boolean more;
                do {
                    var page = files.list(new FileListRequest(directory, offset, 256));
                    for (var item : page.entries()) {
                        var metadata = item.metadata();
                        if (ignored(metadata.path().projectPath())) continue;
                        FileIndexEntry entry = entry(workspace.projectId(), metadata.path(), generation.value());
                        entries.put(entry.path(), entry);
                        if (entry.type() == FileType.DIRECTORY) directories.addLast(metadata.path());
                        else indexContent(metadata.path(), entry, generation, symbols, documents);
                    }
                    more = page.truncated();
                    offset = page.nextOffset();
                } while (more);
            }
            reference.set(new Snapshot(generation, IndexStatus.READY, entries, symbols, documents, time.now()));
            return generation;
        }
    }

    public IndexGeneration apply(FileChangeSet changeSet) {
        Objects.requireNonNull(changeSet, "changeSet must not be null");
        if (changeSet.status() != FileChangeSetStatus.APPLIED && changeSet.status() != FileChangeSetStatus.RECONCILED) {
            throw new IllegalArgumentException("only complete change sets may update the index");
        }
        AtomicReference<Snapshot> reference = snapshots.get(changeSet.workspaceId());
        if (reference == null || reference.get() == null) return rebuild(changeSet.workspaceId());
        synchronized (reference) {
            Snapshot current = reference.get();
            IndexGeneration next = current.generation().next();
            Map<ProjectPath, FileIndexEntry> entries = new HashMap<>(current.files());
            Map<ProjectPath, List<SymbolRecord>> symbols = new HashMap<>(current.symbols());
            Map<ProjectPath, List<DocumentNode>> documents = new HashMap<>(current.documents());
            for (var change : changeSet.changes()) {
                remove(change.path(), entries, symbols, documents);
                ProjectPath target = change.optionalDestination().orElse(change.path());
                if (change.optionalAfter().isPresent()) {
                    if (change.after().type() == FileType.DIRECTORY) return rebuild(changeSet.workspaceId());
                    WorkspacePath path = new WorkspacePath(changeSet.workspaceId(), target);
                    try {
                        FileIndexEntry entry = entry(changeSet.projectId(), path, next.value());
                        entries.put(target, entry);
                        indexContent(path, entry, next, symbols, documents);
                    } catch (WorkspaceFileException unavailable) {
                        reference.set(current.withStatus(IndexStatus.SUSPECT));
                        return current.generation();
                    }
                }
            }
            entries.replaceAll((path, entry) -> withGeneration(entry, next.value()));
            symbols.replaceAll((path, values) -> values.stream()
                    .map(value -> withGeneration(value, next.value()))
                    .toList());
            documents.replaceAll((path, values) -> values.stream()
                    .map(value -> withGeneration(value, next.value()))
                    .toList());
            reference.set(new Snapshot(next, IndexStatus.READY, entries, symbols, documents, time.now()));
            return next;
        }
    }

    public void markSuspect(WorkspaceId workspaceId) {
        AtomicReference<Snapshot> reference = snapshots.get(workspaceId);
        if (reference != null)
            reference.updateAndGet(snapshot -> snapshot == null ? null : snapshot.withStatus(IndexStatus.SUSPECT));
    }

    public FileIndexResult queryFiles(FileIndexQuery query) {
        Snapshot snapshot = requireSnapshot(query.workspaceId());
        List<FileIndexEntry> matches = snapshot.files().values().stream()
                .filter(entry -> under(entry.path(), query.prefix()))
                .filter(entry -> query.text().isEmpty()
                        || entry.path().value().toLowerCase(Locale.ROOT).contains(query.text()))
                .sorted(Comparator.comparing(FileIndexEntry::path))
                .filter(this::currentlyAuthorized)
                .toList();
        int from = Math.min(query.offset(), matches.size());
        int to = Math.min(from + query.limit(), matches.size());
        return new FileIndexResult(
                snapshot.generation(), snapshot.status(), matches.subList(from, to), to < matches.size());
    }

    public List<SymbolRecord> querySymbols(WorkspaceId workspaceId, String text, int limit) {
        requireLimit(limit);
        String term = Objects.requireNonNull(text, "text must not be null").toLowerCase(Locale.ROOT);
        Snapshot snapshot = requireSnapshot(workspaceId);
        return snapshot.symbols().values().stream()
                .flatMap(List::stream)
                .filter(symbol -> symbol.name().toLowerCase(Locale.ROOT).contains(term)
                        || symbol.qualifiedName().toLowerCase(Locale.ROOT).contains(term))
                .filter(symbol -> currentlyAuthorizedPath(workspaceId, symbol.path()))
                .sorted(Comparator.comparing(SymbolRecord::qualifiedName).thenComparing(SymbolRecord::path))
                .limit(limit)
                .toList();
    }

    public List<DocumentNode> queryDocuments(WorkspaceId workspaceId, String text, int limit) {
        requireLimit(limit);
        String term = Objects.requireNonNull(text, "text must not be null").toLowerCase(Locale.ROOT);
        Snapshot snapshot = requireSnapshot(workspaceId);
        return snapshot.documents().values().stream()
                .flatMap(List::stream)
                .filter(node -> node.title().toLowerCase(Locale.ROOT).contains(term)
                        || node.summary().toLowerCase(Locale.ROOT).contains(term))
                .filter(node -> currentlyAuthorizedPath(workspaceId, node.path()))
                .sorted(Comparator.comparing(DocumentNode::path).thenComparingInt(DocumentNode::startLine))
                .limit(limit)
                .toList();
    }

    public IndexStatus status(WorkspaceId workspaceId) {
        return requireSnapshot(workspaceId).status();
    }

    private FileIndexEntry entry(
            io.haifa.agent.project.domain.ProjectId projectId, WorkspacePath path, long generation) {
        var metadata = files.stat(
                path,
                path.projectPath().value().toLowerCase(Locale.ROOT).endsWith(".java")
                        || path.projectPath().value().toLowerCase(Locale.ROOT).endsWith(".md"));
        String hash = metadata.contentHash().orElse("metadata:" + metadata.type() + ":" + metadata.size());
        return new FileIndexEntry(
                projectId,
                path.workspaceId(),
                path.projectPath(),
                metadata.type(),
                metadata.size(),
                metadata.lastModifiedAt().orElseGet(time::now),
                hash,
                language(path.projectPath()),
                generation,
                false);
    }

    private void indexContent(
            WorkspacePath path,
            FileIndexEntry entry,
            IndexGeneration generation,
            Map<ProjectPath, List<SymbolRecord>> symbols,
            Map<ProjectPath, List<DocumentNode>> documents) {
        if (entry.size() > MAX_SOURCE_BYTES || entry.type() != FileType.FILE) return;
        String lower = path.projectPath().value().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".java") && !lower.endsWith(".md") && !lower.endsWith(".markdown")) return;
        try {
            String text = files.read(
                            path, new ReadOptions(MAX_SOURCE_BYTES, MAX_SOURCE_BYTES, StandardCharsets.UTF_8, false))
                    .text();
            if (lower.endsWith(".java"))
                symbols.put(path.projectPath(), parseJava(path, text, entry.contentHash(), generation));
            else documents.put(path.projectPath(), parseMarkdown(path, text, entry.contentHash(), generation));
        } catch (RuntimeException fileParseFailure) {
            symbols.remove(path.projectPath());
            documents.remove(path.projectPath());
        }
    }

    private static List<SymbolRecord> parseJava(
            WorkspacePath path, String text, String contentHash, IndexGeneration generation) {
        List<SymbolRecord> result = new ArrayList<>();
        String packageName = "";
        String currentType = "";
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            var packageMatch = PACKAGE.matcher(line);
            if (packageMatch.find()) packageName = packageMatch.group(1);
            var typeMatch = TYPE.matcher(line);
            if (typeMatch.find()) {
                currentType = typeMatch.group(3);
                SymbolKind kind = SymbolKind.valueOf(typeMatch.group(2).toUpperCase(Locale.ROOT));
                result.add(new SymbolRecord(
                        path.workspaceId(),
                        currentType,
                        qualify(packageName, currentType),
                        kind,
                        path.projectPath(),
                        index + 1,
                        typeMatch.start(3) + 1,
                        typeMatch.group(),
                        visibility(typeMatch.group(1)),
                        contentHash,
                        generation.value()));
                continue;
            }
            var methodMatch = METHOD.matcher(line);
            if (methodMatch.find() && !currentType.isEmpty()) {
                String name = methodMatch.group(2);
                if (Set.of("if", "for", "while", "switch", "catch", "return", "new")
                        .contains(name)) continue;
                SymbolKind kind = name.equals(currentType) ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD;
                result.add(new SymbolRecord(
                        path.workspaceId(),
                        name,
                        qualify(packageName, currentType + "." + name),
                        kind,
                        path.projectPath(),
                        index + 1,
                        methodMatch.start(2) + 1,
                        name + "(" + methodMatch.group(3).trim() + ")",
                        visibility(methodMatch.group(1)),
                        contentHash,
                        generation.value()));
            }
        }
        return List.copyOf(result);
    }

    private static List<DocumentNode> parseMarkdown(
            WorkspacePath path, String text, String contentHash, IndexGeneration generation) {
        List<DocumentNode> result = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        boolean fenced = false;
        Map<String, Integer> duplicates = new HashMap<>();
        for (int index = 0; index < lines.length; index++) {
            String trimmed = lines[index].trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fenced = !fenced;
                continue;
            }
            if (fenced) continue;
            int level = 0;
            String title = "";
            if (trimmed.matches("^#{1,6}\\s+.+$")) {
                level = trimmed.indexOf(' ');
                title = trimmed.substring(level + 1)
                        .replaceFirst("\\s+#+\\s*$", "")
                        .trim();
            } else if (index + 1 < lines.length
                    && !trimmed.isEmpty()
                    && lines[index + 1].trim().matches("^(=+|-+)$")) {
                level = lines[index + 1].trim().charAt(0) == '=' ? 1 : 2;
                title = trimmed;
            }
            if (level == 0 || title.isEmpty()) continue;
            String key = slug(title);
            int duplicate = duplicates.merge(key, 1, Integer::sum);
            String nodeId = path.projectPath().value() + "#" + key + (duplicate == 1 ? "" : "-" + duplicate);
            String summary = sectionSummary(lines, index + 1);
            result.add(new DocumentNode(
                    path.workspaceId(),
                    nodeId,
                    DocumentNodeKind.HEADING,
                    title,
                    level,
                    path.projectPath(),
                    index + 1,
                    index + 1,
                    summary,
                    contentHash,
                    generation.value()));
        }
        return List.copyOf(result);
    }

    private boolean currentlyAuthorized(FileIndexEntry entry) {
        return currentlyAuthorizedPath(entry.workspaceId(), entry.path());
    }

    private boolean currentlyAuthorizedPath(WorkspaceId workspaceId, ProjectPath path) {
        try {
            files.stat(new WorkspacePath(workspaceId, path), false);
            return true;
        } catch (WorkspaceFileException deniedOrMissing) {
            return false;
        }
    }

    private Snapshot requireSnapshot(WorkspaceId workspaceId) {
        AtomicReference<Snapshot> reference = snapshots.get(workspaceId);
        Snapshot snapshot = reference == null ? null : reference.get();
        if (snapshot == null || snapshot.status() == IndexStatus.UNAVAILABLE) {
            throw new IllegalStateException("project index is unavailable");
        }
        return snapshot;
    }

    private static void remove(
            ProjectPath path,
            Map<ProjectPath, FileIndexEntry> entries,
            Map<ProjectPath, List<SymbolRecord>> symbols,
            Map<ProjectPath, List<DocumentNode>> documents) {
        entries.keySet().removeIf(candidate -> under(candidate, path));
        symbols.keySet().removeIf(candidate -> under(candidate, path));
        documents.keySet().removeIf(candidate -> under(candidate, path));
    }

    private static boolean ignored(ProjectPath path) {
        for (String segment : path.segments()) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (IGNORED_SEGMENTS.contains(lower)
                    || lower.equals(".env")
                    || lower.endsWith(".pem")
                    || lower.endsWith(".key")
                    || lower.endsWith(".p12")
                    || lower.endsWith(".pfx")) return true;
        }
        return false;
    }

    private static boolean under(ProjectPath candidate, ProjectPath prefix) {
        return prefix.isRoot() || candidate.equals(prefix) || candidate.value().startsWith(prefix.value() + "/");
    }

    private static String language(ProjectPath path) {
        String value = path.value().toLowerCase(Locale.ROOT);
        if (value.endsWith(".java")) return "java";
        if (value.endsWith(".md") || value.endsWith(".markdown")) return "markdown";
        int dot = value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot + 1);
    }

    private static String visibility(String value) {
        return value == null ? "package" : value;
    }

    private static String qualify(String packageName, String name) {
        return packageName.isEmpty() ? name : packageName + "." + name;
    }

    private static String slug(String title) {
        String value = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-|-$", "");
        return value.isEmpty() ? sha256(title).substring(0, 12) : value;
    }

    private static String sectionSummary(String[] lines, int start) {
        StringBuilder value = new StringBuilder();
        for (int index = start; index < lines.length && value.length() < 240; index++) {
            String line = lines[index].trim();
            if (line.matches("^#{1,6}\\s+.+$") || line.matches("^(=+|-+)$")) break;
            if (!line.isEmpty()) value.append(value.isEmpty() ? "" : " ").append(line);
        }
        return value.length() <= 240 ? value.toString() : value.substring(0, 240);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit is out of range");
    }

    private static FileIndexEntry withGeneration(FileIndexEntry value, long generation) {
        return new FileIndexEntry(
                value.projectId(),
                value.workspaceId(),
                value.path(),
                value.type(),
                value.size(),
                value.observedAt(),
                value.contentHash(),
                value.language(),
                generation,
                value.truncated());
    }

    private static SymbolRecord withGeneration(SymbolRecord value, long generation) {
        return new SymbolRecord(
                value.workspaceId(),
                value.name(),
                value.qualifiedName(),
                value.kind(),
                value.path(),
                value.startLine(),
                value.startColumn(),
                value.signature(),
                value.visibility(),
                value.contentHash(),
                generation);
    }

    private static DocumentNode withGeneration(DocumentNode value, long generation) {
        return new DocumentNode(
                value.workspaceId(),
                value.nodeId(),
                value.kind(),
                value.title(),
                value.level(),
                value.path(),
                value.startLine(),
                value.endLine(),
                value.summary(),
                value.contentHash(),
                generation);
    }

    private record Snapshot(
            IndexGeneration generation,
            IndexStatus status,
            Map<ProjectPath, FileIndexEntry> files,
            Map<ProjectPath, List<SymbolRecord>> symbols,
            Map<ProjectPath, List<DocumentNode>> documents,
            Instant completedAt) {
        private Snapshot {
            files = Map.copyOf(files);
            symbols = Map.copyOf(symbols);
            documents = Map.copyOf(documents);
        }

        private Snapshot withStatus(IndexStatus next) {
            return new Snapshot(generation, next, files, symbols, documents, completedAt);
        }
    }
}
