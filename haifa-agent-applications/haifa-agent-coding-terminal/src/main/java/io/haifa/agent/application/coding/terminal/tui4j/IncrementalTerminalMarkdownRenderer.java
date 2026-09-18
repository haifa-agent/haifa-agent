package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.ansi.TextWidth;
import com.williamcallahan.tui4j.ansi.TextWrapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Incremental, terminal-oriented renderer for the deliberately small Markdown subset used by
 * assistant responses.
 *
 * <p>Append-only streaming updates consume only the new suffix. A reset is reserved for content
 * replacement or the transcript's bounded-tail rollover, where the removed prefix can change
 * Markdown block state.
 */
final class IncrementalTerminalMarkdownRenderer {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern UNORDERED_LIST = Pattern.compile("^(\\s*)[-+*]\\s+(.*)$");
    private static final Pattern ORDERED_LIST = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^>\\s?(.*)$");
    private static final String FENCE = "```";

    private final Tui4jTerminalTheme theme;
    private final TextWrapper wrapper = new TextWrapper();
    private final Map<String, DocumentState> documents = new HashMap<>();

    IncrementalTerminalMarkdownRenderer(Tui4jTerminalTheme theme) {
        this.theme = theme;
    }

    String render(String id, String markdown, int width) {
        DocumentState document = documents.computeIfAbsent(id, ignored -> new DocumentState());
        document.update(sanitize(markdown));
        int safeWidth = Math.max(12, width);
        return document.lines().stream()
                .map(line -> renderLine(line, safeWidth))
                .filter(value -> value != null)
                .collect(Collectors.joining("\n"));
    }

    void retain(Set<String> ids) {
        documents.keySet().retainAll(ids);
    }

    ParseMetrics metrics(String id) {
        DocumentState document = documents.get(id);
        return document == null
                ? new ParseMetrics(0, 0, 0)
                : new ParseMetrics(
                        document.fullParseCount, document.lastDeltaCharacters, document.totalParsedCharacters);
    }

    private String renderLine(MarkdownLine line, int width) {
        return switch (line.kind()) {
            case BLANK -> "";
            case HIDDEN -> null;
            case PARAGRAPH -> wrap(renderInline(line.content()), width, "");
            case HEADING -> wrap(theme.heading(renderInline(line.content())), width, "");
            case QUOTE -> wrap(theme.quote(renderInline(line.content())), width, theme.muted("│ "));
            case LIST_ITEM -> wrap(renderInline(line.content()), width, line.prefix());
            case CODE_HEADER ->
                line.content().isBlank() ? theme.muted("code") : theme.muted("code · " + line.content());
            case CODE -> wrap(theme.codeBlock(line.content()), width, "  ");
        };
    }

    private String wrap(String styled, int width, String prefix) {
        int contentWidth = Math.max(4, width - TextWidth.measureCellWidth(prefix));
        String wrapped = wrapper.wrap(styled, contentWidth, true);
        String continuation = " ".repeat(Math.max(0, TextWidth.measureCellWidth(prefix)));
        String[] lines = wrapped.split("\n", -1);
        StringBuilder result = new StringBuilder(styled.length() + prefix.length() * lines.length);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) result.append('\n');
            result.append(index == 0 ? prefix : continuation).append(lines[index]);
        }
        return result.toString();
    }

    private String renderInline(String value) {
        StringBuilder rendered = new StringBuilder(value.length());
        StringBuilder plain = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            if (value.charAt(index) == '\\' && index + 1 < value.length()) {
                plain.append(value.charAt(index + 1));
                index += 2;
                continue;
            }
            if (value.startsWith("![", index)) {
                int labelEnd = value.indexOf("](", index + 2);
                int urlEnd = labelEnd < 0 ? -1 : value.indexOf(')', labelEnd + 2);
                if (labelEnd >= 0 && urlEnd >= 0) {
                    flushPlain(rendered, plain);
                    String label = value.substring(index + 2, labelEnd);
                    String url = value.substring(labelEnd + 2, urlEnd);
                    rendered.append("[image: ").append(label).append("] ").append(url);
                    index = urlEnd + 1;
                    continue;
                }
            }
            if (value.charAt(index) == '[') {
                int labelEnd = value.indexOf("](", index + 1);
                int urlEnd = labelEnd < 0 ? -1 : value.indexOf(')', labelEnd + 2);
                if (labelEnd >= 0 && urlEnd >= 0) {
                    flushPlain(rendered, plain);
                    String label = value.substring(index + 1, labelEnd);
                    String url = value.substring(labelEnd + 2, urlEnd);
                    rendered.append(renderInline(label))
                            .append(" (")
                            .append(theme.link(url))
                            .append(')');
                    index = urlEnd + 1;
                    continue;
                }
            }
            if (value.charAt(index) == '`') {
                int end = value.indexOf('`', index + 1);
                if (end > index + 1) {
                    flushPlain(rendered, plain);
                    rendered.append(theme.inlineCode(value.substring(index + 1, end)));
                    index = end + 1;
                    continue;
                }
                flushPlain(rendered, plain);
                rendered.append(theme.inlineCode(value.substring(index + 1)));
                break;
            }
            if (value.startsWith("**", index)) {
                int end = value.indexOf("**", index + 2);
                if (end > index + 2) {
                    flushPlain(rendered, plain);
                    rendered.append(theme.strong(value.substring(index + 2, end)));
                    index = end + 2;
                    continue;
                }
                flushPlain(rendered, plain);
                rendered.append(theme.strong(value.substring(index + 2)));
                break;
            }
            if (value.charAt(index) == '*') {
                int end = value.indexOf('*', index + 1);
                if (end > index + 1) {
                    flushPlain(rendered, plain);
                    rendered.append(theme.emphasis(value.substring(index + 1, end)));
                    index = end + 1;
                    continue;
                }
                flushPlain(rendered, plain);
                rendered.append(theme.emphasis(value.substring(index + 1)));
                break;
            }
            int codePoint = value.codePointAt(index);
            plain.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
        }
        flushPlain(rendered, plain);
        return rendered.toString();
    }

    private void flushPlain(StringBuilder rendered, StringBuilder plain) {
        if (!plain.isEmpty()) {
            rendered.append(plain);
            plain.setLength(0);
        }
    }

    private static MarkdownLine parseLine(String raw, boolean insideFence) {
        String trimmed = raw.strip();
        if (trimmed.startsWith(FENCE)) {
            return insideFence
                    ? new MarkdownLine(LineKind.HIDDEN, "", "")
                    : new MarkdownLine(
                            LineKind.CODE_HEADER,
                            trimmed.substring(FENCE.length()).strip(),
                            "");
        }
        if (insideFence) return new MarkdownLine(LineKind.CODE, raw, "");
        if (raw.isBlank()) return new MarkdownLine(LineKind.BLANK, "", "");

        Matcher heading = HEADING.matcher(raw);
        if (heading.matches()) return new MarkdownLine(LineKind.HEADING, heading.group(2), "");

        Matcher quote = QUOTE.matcher(raw);
        if (quote.matches()) return new MarkdownLine(LineKind.QUOTE, quote.group(1), "");

        Matcher unordered = UNORDERED_LIST.matcher(raw);
        if (unordered.matches()) {
            return new MarkdownLine(LineKind.LIST_ITEM, unordered.group(2), listIndent(unordered.group(1)) + "• ");
        }

        Matcher ordered = ORDERED_LIST.matcher(raw);
        if (ordered.matches()) {
            return new MarkdownLine(
                    LineKind.LIST_ITEM, ordered.group(3), listIndent(ordered.group(1)) + ordered.group(2) + ". ");
        }
        return new MarkdownLine(LineKind.PARAGRAPH, raw.strip(), "");
    }

    private static String listIndent(String whitespace) {
        int level = Math.min(1, whitespace.replace("\t", "    ").length() / 2);
        return "  ".repeat(level);
    }

    private static String sanitize(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\n') {
                safe.appendCodePoint(codePoint);
            } else if (codePoint == '\t') {
                safe.append("    ");
            } else if (!Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString();
    }

    record ParseMetrics(int fullParseCount, int lastDeltaCharacters, long totalParsedCharacters) {}

    private enum LineKind {
        BLANK,
        HIDDEN,
        PARAGRAPH,
        HEADING,
        QUOTE,
        LIST_ITEM,
        CODE_HEADER,
        CODE
    }

    private record MarkdownLine(LineKind kind, String content, String prefix) {}

    private static final class DocumentState {
        private final List<MarkdownLine> completed = new ArrayList<>();
        private final StringBuilder pending = new StringBuilder();
        private String source = "";
        private boolean insideFence;
        private int fullParseCount = 1;
        private int lastDeltaCharacters;
        private long totalParsedCharacters;

        private void update(String next) {
            if (!next.startsWith(source)) {
                reset();
            }
            String delta = next.substring(source.length());
            lastDeltaCharacters = delta.length();
            consume(delta);
            source = next;
        }

        private void reset() {
            completed.clear();
            pending.setLength(0);
            source = "";
            insideFence = false;
            fullParseCount++;
        }

        private void consume(String delta) {
            totalParsedCharacters += delta.length();
            for (int index = 0; index < delta.length(); index++) {
                char value = delta.charAt(index);
                if (value == '\n') {
                    finishLine();
                } else {
                    pending.append(value);
                }
            }
        }

        private void finishLine() {
            String raw = pending.toString();
            if (raw.endsWith("\r")) raw = raw.substring(0, raw.length() - 1);
            MarkdownLine line = parseLine(raw, insideFence);
            completed.add(line);
            if (raw.strip().startsWith(FENCE)) insideFence = !insideFence;
            pending.setLength(0);
        }

        private List<MarkdownLine> lines() {
            if (pending.isEmpty()) return List.copyOf(completed);
            List<MarkdownLine> values = new ArrayList<>(completed.size() + 1);
            values.addAll(completed);
            values.add(parseLine(pending.toString(), insideFence));
            return values;
        }
    }
}
