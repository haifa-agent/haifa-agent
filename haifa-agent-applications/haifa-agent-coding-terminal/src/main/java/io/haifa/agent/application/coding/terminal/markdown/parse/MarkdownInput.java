package io.haifa.agent.application.coding.terminal.markdown.parse;

/** Removes input terminal commands as complete sequences, while preserving newline and tab semantics. */
public final class MarkdownInput {
    private MarkdownInput() {}

    public static String sanitize(String input) {
        StringBuilder result = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); ) {
            int cp = input.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 27 || cp == 0x9b || cp == 0x9d) {
                int kind = cp == 27 && i < input.length() ? input.charAt(i++) : cp == 0x9b ? '[' : ']';
                if (kind == '[') {
                    while (i < input.length()) {
                        char ch = input.charAt(i++);
                        if (ch >= '@' && ch <= '~') break;
                    }
                } else if (kind == ']' || kind == 'P' || kind == '^' || kind == '_') {
                    while (i < input.length()) {
                        char ch = input.charAt(i++);
                        if (ch == 7 || ch == 0x9c) break;
                        if (ch == 27 && i < input.length() && input.charAt(i) == '\\') {
                            i++;
                            break;
                        }
                    }
                }
            } else if (cp == '\r') {
                if (i < input.length() && input.charAt(i) == '\n') continue;
            } else if (cp == '\n') {
                result.append('\n');
            } else if (cp == '\t') {
                result.append("    ");
            } else if (!Character.isISOControl(cp)) {
                result.appendCodePoint(cp);
            }
        }
        return result.toString();
    }
}
