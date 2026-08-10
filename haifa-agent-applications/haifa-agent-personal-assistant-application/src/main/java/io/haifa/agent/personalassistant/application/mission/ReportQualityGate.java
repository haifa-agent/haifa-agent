package io.haifa.agent.personalassistant.application.mission;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, model-free checks for a Deep Research Markdown report candidate. */
public final class ReportQualityGate {
    public static final int MAX_REPORT_BYTES = 256_000;
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "executive-summary",
            "scope-method",
            "task-findings",
            "synthesis",
            "conclusions",
            "risks-unknowns",
            "sources");
    private static final Pattern SECTION_MARKER =
            Pattern.compile("<!--\\s*haifa-section:\\s*([a-z0-9-]+)\\s*-->", Pattern.CASE_INSENSITIVE);
    private static final Pattern TASK_MARKER =
            Pattern.compile("<!--\\s*haifa-task:\\s*([a-z0-9][a-z0-9-]{0,127})\\s*-->", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_CITATION =
            Pattern.compile("\\[\\[([a-z0-9][a-z0-9-]{0,127})]]", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern MARKDOWN_DECORATION = Pattern.compile("(?m)^[#>*+`|: -]+|[\\[\\]()`*_~]");

    public Result evaluate(String markdown, List<String> requiredTaskIds, Set<String> availableSourceIds) {
        List<Failure> failures = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return new Result(false, List.of(new Failure("REPORT_EMPTY", List.of())));
        }
        if (markdown.getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES) {
            failures.add(new Failure("REPORT_TOO_LARGE", List.of()));
        }

        var sections = markerRanges(markdown, SECTION_MARKER);
        List<String> missingSections = REQUIRED_SECTIONS.stream()
                .filter(section -> !sections.contains(section))
                .toList();
        if (!missingSections.isEmpty()) {
            failures.add(new Failure("REPORT_REQUIRED_SECTION_MISSING", missingSections));
        }
        List<String> emptySections = REQUIRED_SECTIONS.stream()
                .filter(sections::contains)
                .filter(section -> !hasSubstantiveText(
                        sectionBody(markdown, section, SECTION_MARKER), section.equals("sources") ? 8 : 24))
                .toList();
        if (!emptySections.isEmpty()) {
            failures.add(new Failure("REPORT_SECTION_EMPTY", emptySections));
        }

        Set<String> taskMarkers = markerRanges(markdown, TASK_MARKER);
        List<String> uncoveredTasks = requiredTaskIds.stream()
                .filter(taskId -> !taskMarkers.contains(taskId.toLowerCase(java.util.Locale.ROOT)))
                .toList();
        for (String taskId : requiredTaskIds) {
            if (taskMarkers.contains(taskId.toLowerCase(java.util.Locale.ROOT))
                    && !hasSubstantiveText(taskBody(markdown, taskId), 24)
                    && !uncoveredTasks.contains(taskId)) {
                uncoveredTasks = append(uncoveredTasks, taskId);
            }
        }
        if (!uncoveredTasks.isEmpty()) {
            failures.add(new Failure("REPORT_TASK_COVERAGE_MISSING", uncoveredTasks));
        }

        Set<String> citations = markerRanges(markdown, SOURCE_CITATION);
        if (!availableSourceIds.isEmpty() && citations.isEmpty()) {
            failures.add(new Failure("REPORT_SOURCES_MISSING", List.of()));
        }
        List<String> invalidCitations = citations.stream()
                .filter(sourceId -> !availableSourceIds.contains(sourceId))
                .toList();
        if (!invalidCitations.isEmpty()) {
            failures.add(new Failure("REPORT_CITATION_INVALID", invalidCitations));
        }
        if (!hasSubstantiveText(markdown, 160)) {
            failures.add(new Failure("REPORT_ONLY_METADATA", List.of()));
        }
        return new Result(failures.isEmpty(), failures);
    }

    public boolean readable(String markdown) {
        return markdown != null
                && !markdown.isBlank()
                && markdown.getBytes(StandardCharsets.UTF_8).length <= MAX_REPORT_BYTES
                && hasSubstantiveText(markdown, 40);
    }

    private static Set<String> markerRanges(String markdown, Pattern pattern) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(markdown);
        while (matcher.find()) {
            values.add(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
        }
        return Set.copyOf(values);
    }

    private static String sectionBody(String markdown, String section, Pattern marker) {
        Matcher matcher = marker.matcher(markdown);
        int start = -1;
        while (matcher.find()) {
            if (start >= 0) return markdown.substring(start, matcher.start());
            if (section.equalsIgnoreCase(matcher.group(1))) start = matcher.end();
        }
        return start < 0 ? "" : markdown.substring(start);
    }

    private static String taskBody(String markdown, String taskId) {
        Matcher matcher = TASK_MARKER.matcher(markdown);
        int start = -1;
        while (matcher.find()) {
            if (start >= 0) return markdown.substring(start, matcher.start());
            if (taskId.equalsIgnoreCase(matcher.group(1))) start = matcher.end();
        }
        if (start < 0) return "";
        Matcher section = SECTION_MARKER.matcher(markdown);
        section.region(start, markdown.length());
        return section.find() ? markdown.substring(start, section.start()) : markdown.substring(start);
    }

    private static boolean hasSubstantiveText(String value, int minimumCharacters) {
        String normalized = COMMENT.matcher(value).replaceAll(" ");
        normalized = MARKDOWN_DECORATION.matcher(normalized).replaceAll(" ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized.codePointCount(0, normalized.length()) >= minimumCharacters;
    }

    private static List<String> append(List<String> values, String value) {
        ArrayList<String> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    public record Result(boolean passed, List<Failure> failures) {
        public Result {
            failures = List.copyOf(failures);
            if (passed != failures.isEmpty()) {
                throw new IllegalArgumentException("Report quality result is inconsistent");
            }
        }

        public static Result passedResult() {
            return new Result(true, List.of());
        }

        public List<String> failureCodes() {
            return failures.stream().map(Failure::code).distinct().toList();
        }

        public List<String> affectedTaskIds() {
            return failures.stream()
                    .filter(failure -> "REPORT_TASK_COVERAGE_MISSING".equals(failure.code()))
                    .flatMap(failure -> failure.details().stream())
                    .distinct()
                    .toList();
        }

        public String revisionFeedback() {
            return failures.stream()
                    .map(failure -> failure.code()
                            + (failure.details().isEmpty() ? "" : ":" + String.join(",", failure.details())))
                    .collect(java.util.stream.Collectors.joining("; "));
        }
    }

    public record Failure(String code, List<String> details) {
        public Failure {
            code = MissionValues.text(code, "code", 128);
            details = List.copyOf(details);
        }
    }
}
