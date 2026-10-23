package io.haifa.agent.application.coding.terminal.markdown.parse;

import static io.haifa.agent.application.coding.terminal.markdown.parse.MarkdownDocument.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for assistant headings, nested containers, fenced code, inline markup and pipe tables. */
public final class MarkdownParser {
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.*)$");
    private static final Pattern LIST = Pattern.compile("^( *)([-+*]|[0-9]{1,9}[.)])\\s+(.*)$");
    private static final Pattern FENCE = Pattern.compile("^ *(`{3,}|~{3,})(.*)$");
    private static final Pattern RULE = Pattern.compile("^ *(?:(?:-\\s*){3,}|(?:\\*\\s*){3,}|(?:_\\s*){3,})$");
    private static final int MAX_DEPTH = 32;

    private record Line(String text, int start, int end, boolean terminated) {
        Line withText(String value) {
            return new Line(value, start, end, terminated);
        }
    }

    public MarkdownDocument parse(String source) {
        List<Line> lines = new ArrayList<>();
        for (int start = 0; start < source.length(); ) {
            int newline = source.indexOf('\n', start);
            int end = newline < 0 ? source.length() : newline;
            lines.add(new Line(source.substring(start, end), start, newline < 0 ? end : end + 1, newline >= 0));
            start = newline < 0 ? source.length() : end + 1;
        }
        return new MarkdownDocument(blocks(lines, 0, 0));
    }

    private List<LocatedBlock> blocks(List<Line> lines, int depth, int listDepth) {
        List<LocatedBlock> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); ) {
            int first = i;
            Line line = lines.get(i);
            String raw = line.text();
            Matcher heading = HEADING.matcher(raw);
            Matcher fence = FENCE.matcher(raw);
            Matcher list = LIST.matcher(raw);
            Block block;
            boolean closed = true;
            if (raw.isBlank()) {
                while (++i < lines.size() && lines.get(i).text().isBlank()) {
                    /* structural blanks collapse */
                }
                block = new Blank();
            } else if (fence.matches()) {
                String marker = fence.group(1);
                List<String> code = new ArrayList<>();
                closed = false;
                i++;
                while (i < lines.size()) {
                    String candidate = lines.get(i).text().strip();
                    if (candidate.length() >= marker.length()
                            && candidate.chars().allMatch(ch -> ch == marker.charAt(0))) {
                        closed = true;
                        i++;
                        break;
                    }
                    code.add(lines.get(i++).text());
                }
                block = new CodeBlock(fence.group(2).strip(), code, closed);
            } else if (heading.matches()) {
                block = new Heading(heading.group(1).length(), inline(heading.group(2), 0));
                i++;
            } else if (RULE.matcher(raw).matches()) {
                block = new Rule();
                i++;
            } else if (raw.stripLeading().startsWith(">") && depth < MAX_DEPTH) {
                List<Line> quoted = new ArrayList<>();
                while (i < lines.size() && lines.get(i).text().stripLeading().startsWith(">")) {
                    Line next = lines.get(i++);
                    quoted.add(next.withText(
                            next.text().stripLeading().substring(1).replaceFirst("^ ", "")));
                }
                List<LocatedBlock> children = blocks(quoted, depth + 1, listDepth);
                block = new Quote(children.stream().map(LocatedBlock::block).toList());
                closed = children.stream().allMatch(LocatedBlock::closed);
            } else if (list.matches() && depth < MAX_DEPTH) {
                boolean ordered = Character.isDigit(list.group(2).charAt(0));
                int base = list.group(1).length();
                List<ListItem> items = new ArrayList<>();
                while (i < lines.size()) {
                    Matcher marker = LIST.matcher(lines.get(i).text());
                    if (!marker.matches()
                            || marker.group(1).length() != base
                            || Character.isDigit(marker.group(2).charAt(0)) != ordered
                            || RULE.matcher(lines.get(i).text()).matches()) break;
                    int contentColumn = marker.start(3);
                    List<Line> body = new ArrayList<>();
                    body.add(lines.get(i++).withText(marker.group(3)));
                    while (i < lines.size()) {
                        Line next = lines.get(i);
                        if (next.text().isBlank()) {
                            int after = i;
                            while (after < lines.size()
                                    && lines.get(after).text().isBlank()) after++;
                            if (after == lines.size()
                                    || indentation(lines.get(after).text()) <= base) break;
                            body.add(next.withText(""));
                            i++;
                        } else if (indentation(next.text()) > base) {
                            int remove = Math.min(contentColumn, indentation(next.text()));
                            body.add(next.withText(next.text().substring(remove)));
                            i++;
                        } else break;
                    }
                    List<LocatedBlock> children = blocks(body, depth + 1, listDepth + base / 2 + 1);
                    items.add(new ListItem(
                            ordered ? marker.group(2).replace(')', '.') : "•",
                            listDepth + base / 2,
                            children.stream().map(LocatedBlock::block).toList()));
                    closed &= children.stream().allMatch(LocatedBlock::closed);
                    int after = i;
                    while (after < lines.size() && lines.get(after).text().isBlank()) after++;
                    if (after < lines.size()) {
                        Matcher next = LIST.matcher(lines.get(after).text());
                        if (next.matches()
                                && next.group(1).length() == base
                                && Character.isDigit(next.group(2).charAt(0)) == ordered
                                && !RULE.matcher(lines.get(after).text()).matches()) i = after;
                    }
                }
                block = new ListBlock(ordered, items);
            } else if (i + 1 < lines.size()
                    && isTableHeader(raw, lines.get(i + 1).text())) {
                List<List<Inline>> header =
                        cells(raw).stream().map(value -> inline(value, 0)).toList();
                i += 2;
                List<List<List<Inline>>> rows = new ArrayList<>();
                while (i < lines.size()
                        && lines.get(i).text().contains("|")
                        && !lines.get(i).text().isBlank()) {
                    List<String> values = cells(lines.get(i++).text());
                    List<List<Inline>> row = new ArrayList<>();
                    for (int c = 0; c < header.size(); c++) row.add(inline(c < values.size() ? values.get(c) : "", 0));
                    rows.add(row);
                }
                block = new Table(header, rows);
            } else {
                StringBuilder paragraph = new StringBuilder(raw.strip());
                i++;
                while (i < lines.size() && !startsBlock(lines, i)) {
                    paragraph.append('\n').append(lines.get(i++).text().strip());
                }
                block = new Paragraph(inline(paragraph.toString(), 0));
            }
            result.add(new LocatedBlock(
                    block, lines.get(first).start(), lines.get(i - 1).end(), closed));
        }
        return result;
    }

    private static int indentation(String text) {
        return text.length() - text.stripLeading().length();
    }

    private static boolean startsBlock(List<Line> lines, int i) {
        String value = lines.get(i).text();
        return value.isBlank()
                || HEADING.matcher(value).matches()
                || LIST.matcher(value).matches()
                || FENCE.matcher(value).matches()
                || RULE.matcher(value).matches()
                || value.stripLeading().startsWith(">")
                || i + 1 < lines.size() && isTableHeader(value, lines.get(i + 1).text());
    }

    private static boolean isTableHeader(String first, String second) {
        List<String> cells = cells(second);
        return first.contains("|")
                && !cells.isEmpty()
                && cells.size() == cells(first).size()
                && cells.stream().allMatch(value -> value.matches(":?-{3,}:?"));
    }

    private static List<String> cells(String value) {
        String stripped = value.strip();
        if (stripped.startsWith("|")) stripped = stripped.substring(1);
        if (stripped.endsWith("|") && !stripped.endsWith("\\|"))
            stripped = stripped.substring(0, stripped.length() - 1);
        List<String> result = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean code = false;
        for (int i = 0; i < stripped.length(); i++) {
            char ch = stripped.charAt(i);
            if (ch == '\\' && i + 1 < stripped.length()) {
                cell.append(ch).append(stripped.charAt(++i));
                continue;
            }
            if (ch == '`') code = !code;
            if (ch == '|' && !code) {
                result.add(cell.toString().strip());
                cell.setLength(0);
            } else cell.append(ch);
        }
        result.add(cell.toString().strip());
        return result;
    }

    private static List<Inline> inline(String value, int depth) {
        if (depth >= MAX_DEPTH) return List.of(new Text(value));
        List<Inline> result = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < value.length(); ) {
            char ch = value.charAt(i);
            if (ch == '\\' && i + 1 < value.length()) {
                text.append(value.charAt(i + 1));
                i += 2;
                continue;
            }
            boolean image = value.startsWith("![", i);
            if (ch == '[' || image) {
                int labelStart = i + (image ? 2 : 1);
                int labelEnd = value.indexOf("](", labelStart);
                int urlEnd = labelEnd < 0 ? -1 : value.indexOf(')', labelEnd + 2);
                if (urlEnd >= 0) {
                    flush(result, text);
                    String label = value.substring(labelStart, labelEnd);
                    String url = value.substring(labelEnd + 2, urlEnd);
                    result.add(image ? new Image(label, url) : new Link(inline(label, depth + 1), url));
                    i = urlEnd + 1;
                    continue;
                }
            }
            if (ch == '`' || ch == '*') {
                String delimiter = value.startsWith("**", i) ? "**" : Character.toString(ch);
                int start = i + delimiter.length();
                int end = closingDelimiter(value, start, delimiter);
                flush(result, text);
                String content = value.substring(start, end < 0 ? value.length() : end);
                result.add(
                        ch == '`'
                                ? new InlineCode(content)
                                : delimiter.length() == 2
                                        ? new Strong(inline(content, depth + 1))
                                        : new Emphasis(inline(content, depth + 1)));
                i = end < 0 ? value.length() : end + delimiter.length();
            } else {
                int cp = value.codePointAt(i);
                text.appendCodePoint(cp);
                i += Character.charCount(cp);
            }
        }
        flush(result, text);
        return List.copyOf(result);
    }

    private static int closingDelimiter(String value, int start, String delimiter) {
        for (int i = start; i < value.length(); i++) {
            if (value.charAt(i) == '\\') {
                i++;
                continue;
            }
            if (!value.startsWith(delimiter, i)) continue;
            if (delimiter.charAt(0) != '*') return i;
            int end = i;
            while (end < value.length() && value.charAt(end) == '*') end++;
            int count = end - i;
            if (delimiter.length() == 2 && count >= 2) return i + (count % 2);
            if (delimiter.length() == 1 && count % 2 == 1) return i;
            i = end - 1;
        }
        return -1;
    }

    private static void flush(List<Inline> result, StringBuilder text) {
        if (!text.isEmpty()) {
            result.add(new Text(text.toString()));
            text.setLength(0);
        }
    }
}
