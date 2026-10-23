package io.haifa.agent.application.coding.terminal.markdown.render;

import static io.haifa.agent.application.coding.terminal.markdown.parse.MarkdownDocument.*;

import com.williamcallahan.tui4j.ansi.TextWidth;
import com.williamcallahan.tui4j.ansi.TextWrapper;
import com.williamcallahan.tui4j.compat.lipgloss.Style;
import com.williamcallahan.tui4j.compat.x.ansi.HardWrap;
import io.haifa.agent.application.coding.terminal.markdown.parse.MarkdownInput;
import io.haifa.agent.application.coding.terminal.markdown.parse.MarkdownParser;
import io.haifa.agent.application.coding.terminal.markdown.style.MarkdownTokens;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Incremental AST facade. Only the mutable top-level tail is reparsed; resize only reflows. */
public final class AstMarkdownRenderer implements MarkdownRenderer {
    private final MarkdownParser parser = new MarkdownParser();
    private final TextWrapper wrapper = new TextWrapper();
    private final Supplier<MarkdownTokens> tokens;
    private final BiFunction<String, List<String>, List<String>> highlighter;
    private final Map<String, Document> documents = new HashMap<>();
    private MarkdownTokens style;

    public AstMarkdownRenderer(
            Supplier<MarkdownTokens> tokens, BiFunction<String, List<String>, List<String>> highlighter) {
        this.tokens = tokens;
        this.highlighter = highlighter;
    }

    @Override
    public String render(String id, String markdown, int width) {
        MarkdownTokens current = tokens.get();
        if (!current.equals(style)) {
            documents.values().forEach(document -> document.rendered.clear());
            style = current;
        }
        Document document = documents.computeIfAbsent(id, ignored -> new Document());
        document.update(MarkdownInput.sanitize(markdown));
        int safeWidth = Math.max(12, width);
        if (safeWidth != document.width) {
            document.rendered.clear();
            document.width = safeWidth;
        }
        List<String> output = new ArrayList<>();
        boolean blank = false;
        Block previous = null;
        Set<Block> retained = new HashSet<>();
        for (LocatedBlock located : document.blocks) {
            Block block = located.block();
            if (block instanceof Blank) {
                blank = true;
                continue;
            }
            if (!output.isEmpty() && (blank || separates(previous, block))) output.add("");
            blank = false;
            retained.add(block);
            output.add(document.rendered.computeIfAbsent(block, key -> renderBlock(key, safeWidth)));
            previous = block;
        }
        document.rendered.keySet().retainAll(retained);
        return String.join("\n", output);
    }

    @Override
    public void retain(Set<String> ids) {
        documents.keySet().retainAll(ids);
    }

    @Override
    public void resetAll() {
        documents.clear();
    }

    @Override
    public ParseMetrics metrics(String id) {
        Document document = documents.get(id);
        return document == null
                ? new ParseMetrics(0, 0, 0)
                : new ParseMetrics(document.fullParses, document.lastDelta, document.parsedCharacters);
    }

    private final class Document {
        private String source = "";
        private List<LocatedBlock> blocks = List.of();
        private int freezeBoundary;
        private int fullParses = 1;
        private int lastDelta;
        private long parsedCharacters;
        private int width;
        private final Map<Block, String> rendered = new HashMap<>();

        void update(String next) {
            if (next.equals(source)) {
                lastDelta = 0;
                return;
            }
            if (!next.startsWith(source)) {
                freezeBoundary = 0;
                blocks = List.of();
                rendered.clear();
                fullParses++;
                lastDelta = next.length();
            } else lastDelta = next.length() - source.length();
            List<LocatedBlock> updated = new ArrayList<>();
            for (LocatedBlock block : blocks) if (block.end() <= freezeBoundary) updated.add(block);
            String tail = next.substring(freezeBoundary);
            parsedCharacters += tail.length();
            for (LocatedBlock block : parser.parse(tail).blocks()) {
                updated.add(new LocatedBlock(
                        block.block(), block.start() + freezeBoundary, block.end() + freezeBoundary, block.closed()));
            }
            blocks = List.copyOf(updated);
            // A blank followed by another top-level block is evidence that the preceding container ended.
            // The final block, including a fence without a newline, always remains mutable.
            boolean closed = true;
            for (int i = 0; i + 1 < blocks.size(); i++) {
                LocatedBlock block = blocks.get(i);
                closed &= block.closed();
                if (closed && block.block() instanceof Blank)
                    freezeBoundary = blocks.get(i + 1).start();
            }
            source = next;
        }
    }

    private static boolean separates(Block first, Block second) {
        if (first == null) return false;
        return first instanceof Heading
                || second instanceof Heading
                || first instanceof Rule
                || second instanceof Rule
                || first.getClass() != second.getClass()
                || first instanceof CodeBlock
                || first instanceof ListBlock
                || first instanceof Quote;
    }

    private String renderBlock(Block block, int width) {
        return switch (block) {
            case Heading heading ->
                wrap(MarkdownStyles.heading(style, heading.level(), inline(heading.content())), width);
            case Paragraph paragraph -> wrap(inline(paragraph.content()), width);
            case Rule ignored -> colored("─".repeat(width), style.rule());
            case Blank ignored -> "";
            case CodeBlock code -> code(code, width);
            case Quote quote -> {
                if (width < 4) yield sequence(quote.blocks(), width);
                String prefix = colored("│ ", style.muted());
                yield prefixLines(sequence(quote.blocks(), width - 2), prefix, prefix);
            }
            case ListBlock list -> list(list, width);
            case Table table -> table(table, width);
        };
    }

    private String sequence(List<Block> blocks, int width) {
        List<String> output = new ArrayList<>();
        Block previous = null;
        boolean blank = false;
        for (Block block : blocks) {
            if (block instanceof Blank) {
                blank = true;
                continue;
            }
            if (!output.isEmpty() && (blank || separates(previous, block))) output.add("");
            output.add(renderBlock(block, width));
            previous = block;
            blank = false;
        }
        return String.join("\n", output);
    }

    private String list(ListBlock list, int width) {
        List<String> output = new ArrayList<>();
        for (ListItem item : list.items()) {
            String indent = "  ".repeat(Math.min(3, item.depth()));
            if (width - TextWidth.measureCellWidth(indent + item.marker() + " ") < 12) indent = "";
            String marker = item.marker() + " ";
            if (width - TextWidth.measureCellWidth(marker) < 2) marker = "• ";
            if (width - TextWidth.measureCellWidth(marker) < 2) marker = "";
            String first = indent + colored(marker, list.ordered() ? style.listNumber() : style.listBullet());
            int prefixWidth = TextWidth.measureCellWidth(first);
            String continuation = " ".repeat(prefixWidth);
            boolean initial = true;
            Block previous = null;
            boolean blank = false;
            for (Block child : item.blocks()) {
                if (child instanceof Blank) {
                    blank = true;
                    continue;
                }
                if (child instanceof ListBlock nested) {
                    output.add(list(nested, width));
                } else {
                    if (!initial && (blank || separates(previous, child))) output.add("");
                    String rendered = renderBlock(child, Math.max(2, width - prefixWidth));
                    output.add(prefixLines(rendered, initial ? first : continuation, continuation));
                }
                previous = child;
                initial = false;
                blank = false;
            }
            if (initial) output.add(first.stripTrailing());
        }
        return String.join("\n", output);
    }

    private String code(CodeBlock code, int width) {
        List<String> lines = new ArrayList<>();
        String label = code.language().isBlank() ? "code" : "code · " + code.language();
        lines.add(colored(label, style.muted()));
        lines.addAll(highlighter.apply(code.language(), code.lines()));
        String border = width >= 4 ? colored("▎ ", style.blockBorder()) : "";
        int contentWidth = Math.max(2, width - TextWidth.measureCellWidth(border));
        List<String> output = new ArrayList<>();
        for (String line : lines) {
            for (String physical : wrap(line, contentWidth).split("\n", -1)) {
                int padding = contentWidth - TextWidth.measureCellWidth(physical);
                output.add(Style.newStyle()
                        .background(MarkdownStyles.color(style.blockBackground()))
                        .render(border + physical + " ".repeat(Math.max(0, padding))));
            }
        }
        return String.join("\n", output);
    }

    private String inline(List<Inline> content) {
        StringBuilder output = new StringBuilder();
        for (Inline node : content) {
            output.append(
                    switch (node) {
                        case Text text -> text.value();
                        case Strong strong -> MarkdownStyles.strong(style, inline(strong.content()));
                        case Emphasis emphasis -> MarkdownStyles.emphasis(style, inline(emphasis.content()));
                        case InlineCode code -> MarkdownStyles.inlineCode(style, code.value());
                        case Link link ->
                            inline(link.label()) + " ("
                                    + Style.newStyle()
                                            .foreground(MarkdownStyles.color(style.heading()))
                                            .underline(true)
                                            .render(link.url())
                                    + ")";
                        case Image image -> "[image: " + image.alt() + "] " + image.url();
                    });
        }
        return output.toString();
    }

    private String colored(String text, MarkdownTokens.Color color) {
        return Style.newStyle().foreground(MarkdownStyles.color(color)).render(text);
    }

    private String wrap(String text, int width) {
        int contentWidth = Math.max(2, width);
        String wrapped = wrapper.wrap(text, contentWidth, true);
        if (wrapped.lines().noneMatch(line -> TextWidth.measureCellWidth(line) > contentWidth)) return wrapped;
        // TextWrapper can leave runs of hyphens over budget. Force-break graphemes, then restore SGR at new lines.
        return wrapper.wrap(HardWrap.hardWrap(text, contentWidth, true), contentWidth, true);
    }

    private static String prefixLines(String text, String first, String continuation) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) lines[i] = (i == 0 ? first : continuation) + lines[i];
        return String.join("\n", lines);
    }

    private String table(Table table, int width) {
        int columns = table.header().size();
        List<List<String>> rows = new ArrayList<>();
        rows.add(table.header().stream().map(this::inline).toList());
        for (List<List<Inline>> row : table.rows())
            rows.add(row.stream().map(this::inline).toList());
        if (width < columns * 5 + 1) {
            List<String> stacked = new ArrayList<>();
            if (rows.size() == 1) return wrap(String.join(" · ", rows.getFirst()), width);
            for (int r = 1; r < rows.size(); r++) {
                if (r > 1) stacked.add("");
                for (int c = 0; c < columns; c++)
                    stacked.add(wrap(rows.getFirst().get(c) + ": " + rows.get(r).get(c), width));
            }
            return String.join("\n", stacked);
        }
        int[] widths = new int[columns];
        Arrays.fill(widths, 2);
        for (List<String> row : rows)
            for (int c = 0; c < columns; c++) {
                widths[c] = Math.max(widths[c], TextWidth.measureCellWidth(row.get(c)));
            }
        int budget = width - columns * 3 - 1;
        widths = fitTableWidths(widths, budget);
        List<String> output = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            List<String[]> cells = new ArrayList<>();
            for (int c = 0; c < columns; c++)
                cells.add(wrap(rows.get(r).get(c), widths[c]).split("\n", -1));
            int height = cells.stream().mapToInt(value -> value.length).max().orElse(1);
            for (int line = 0; line < height; line++) {
                StringBuilder text = new StringBuilder("│");
                for (int c = 0; c < columns; c++) {
                    String value = line < cells.get(c).length ? cells.get(c)[line] : "";
                    text.append(' ')
                            .append(value)
                            .append(" ".repeat(Math.max(0, widths[c] - TextWidth.measureCellWidth(value))))
                            .append(" │");
                }
                output.add(text.toString());
            }
            if (r == 0)
                output.add(colored(
                        "├"
                                + Arrays.stream(widths)
                                        .mapToObj(w -> "─".repeat(w + 2))
                                        .collect(Collectors.joining("┼"))
                                + "┤",
                        style.rule()));
        }
        return String.join("\n", output);
    }

    private static int[] fitTableWidths(int[] natural, int budget) {
        if (Arrays.stream(natural).asLongStream().sum() <= budget) return natural;
        // Find a shared ceiling in O(columns * log(maxWidth)), independent of the number of cells removed.
        int low = 2;
        int high = Arrays.stream(natural).max().orElse(2);
        while (low < high) {
            int ceiling = low + (high - low + 1) / 2;
            long total = 0;
            for (int width : natural) total += Math.min(width, ceiling);
            if (total <= budget) low = ceiling;
            else high = ceiling - 1;
        }
        int[] fitted = new int[natural.length];
        int remaining = budget;
        for (int c = 0; c < natural.length; c++) {
            fitted[c] = Math.min(natural[c], low);
            remaining -= fitted[c];
        }
        // Preserve the old tie-breaking: earlier equally wide columns lose a cell first.
        for (int c = natural.length - 1; c >= 0 && remaining > 0; c--) {
            if (natural[c] > low) {
                fitted[c]++;
                remaining--;
            }
        }
        return fitted;
    }
}
