package io.haifa.agent.project.path;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class ProjectPath implements Comparable<ProjectPath> {
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:.*");
    private static final Set<String> WINDOWS_DEVICES = Set.of(
            "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1",
            "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final String value;

    private ProjectPath(String value) {
        this.value = value;
    }

    public static ProjectPath root() {
        return new ProjectPath("");
    }

    public static ProjectPath of(String input) {
        Objects.requireNonNull(input, "path must not be null");
        if (input.indexOf('\0') >= 0) throw invalid("NUL is not allowed");
        if (input.startsWith("/")
                || input.startsWith("\\")
                || DRIVE.matcher(input).matches()) {
            throw invalid("absolute paths are not allowed");
        }
        String candidate = input.replace('\\', '/');
        if (candidate.contains("://") || candidate.startsWith("//")) throw invalid("URI and UNC paths are not allowed");
        if (candidate.isBlank()) return root();

        List<String> segments = new ArrayList<>();
        for (String raw : candidate.split("/+")) {
            if (raw.isEmpty()) continue;
            String segment = Normalizer.normalize(raw, Normalizer.Form.NFC);
            validateSegment(segment);
            segments.add(segment);
        }
        if (segments.isEmpty()) return root();
        String logical = String.join("/", segments);
        if (logical.length() > 4096) throw invalid("path exceeds 4096 characters");
        return new ProjectPath(logical);
    }

    private static void validateSegment(String segment) {
        if (segment.equals(".") || segment.equals("..")) throw invalid("relative traversal is not allowed");
        if (segment.isBlank()) throw invalid("blank path segments are not allowed");
        if (segment.endsWith(".") || segment.endsWith(" ")) throw invalid("ambiguous trailing dot or space");
        if (segment.indexOf(':') >= 0 || segment.matches(".*[<>\"|?*].*")) {
            throw invalid("cross-platform reserved characters are not allowed");
        }
        String base = segment.contains(".") ? segment.substring(0, segment.indexOf('.')) : segment;
        if (WINDOWS_DEVICES.contains(base.toUpperCase(Locale.ROOT))) throw invalid("reserved device name");
        if (segment.length() > 255) throw invalid("path segment exceeds 255 characters");
    }

    public String value() {
        return value;
    }

    public boolean isRoot() {
        return value.isEmpty();
    }

    public List<String> segments() {
        return value.isEmpty() ? List.of() : List.of(value.split("/"));
    }

    public ProjectPath resolve(String child) {
        ProjectPath normalizedChild = of(child);
        if (normalizedChild.isRoot()) return this;
        return value.isEmpty() ? normalizedChild : of(value + "/" + normalizedChild.value);
    }

    public String comparisonKey(FileSystemSemantics semantics) {
        return Objects.requireNonNull(semantics, "semantics must not be null").comparisonKey(value);
    }

    @Override
    public int compareTo(ProjectPath other) {
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProjectPath path && value.equals(path.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.isEmpty() ? "." : value;
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("invalid project path: " + detail);
    }
}
