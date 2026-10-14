package io.haifa.agent.application.coding.terminal.tui4j;

import java.util.Locale;
import java.util.Set;

/**
 * Lightweight, pure-Java bounded syntax highlighter for terminal code blocks.
 *
 * <p>Never fails or drops characters: strictly preserves original cell width and raw text,
 * degrading safely to uniform code block styling on unknown languages or scanning errors.
 */
final class TerminalSyntaxHighlighter {
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "record",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "yield",
            "sealed",
            "permits",
            "var");

    private static final Set<String> PYTHON_KEYWORDS = Set.of(
            "and",
            "as",
            "assert",
            "async",
            "await",
            "break",
            "class",
            "continue",
            "def",
            "del",
            "elif",
            "else",
            "except",
            "finally",
            "for",
            "from",
            "global",
            "if",
            "import",
            "in",
            "is",
            "lambda",
            "nonlocal",
            "not",
            "or",
            "pass",
            "raise",
            "return",
            "try",
            "while",
            "with",
            "yield",
            "match",
            "case");

    private static final Set<String> JS_KEYWORDS = Set.of(
            "abstract",
            "as",
            "async",
            "await",
            "break",
            "case",
            "catch",
            "class",
            "const",
            "continue",
            "debugger",
            "declare",
            "default",
            "delete",
            "do",
            "else",
            "enum",
            "export",
            "extends",
            "finally",
            "for",
            "from",
            "function",
            "if",
            "implements",
            "import",
            "in",
            "instanceof",
            "interface",
            "let",
            "namespace",
            "new",
            "of",
            "package",
            "private",
            "protected",
            "public",
            "readonly",
            "return",
            "static",
            "super",
            "switch",
            "this",
            "throw",
            "try",
            "type",
            "typeof",
            "var",
            "void",
            "while",
            "with",
            "yield");

    private static final Set<String> GO_KEYWORDS = Set.of(
            "break",
            "case",
            "chan",
            "const",
            "continue",
            "default",
            "defer",
            "else",
            "fallthrough",
            "for",
            "func",
            "go",
            "goto",
            "if",
            "import",
            "interface",
            "map",
            "package",
            "range",
            "return",
            "select",
            "struct",
            "switch",
            "type",
            "var");

    private static final Set<String> SHELL_KEYWORDS = Set.of(
            "alias",
            "case",
            "cd",
            "do",
            "done",
            "echo",
            "elif",
            "else",
            "esac",
            "exit",
            "export",
            "fi",
            "for",
            "function",
            "if",
            "in",
            "local",
            "return",
            "select",
            "set",
            "shift",
            "source",
            "sudo",
            "then",
            "time",
            "until",
            "unset",
            "while");

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "ALL",
            "ALTER",
            "AND",
            "AS",
            "ASC",
            "BETWEEN",
            "BY",
            "CASE",
            "CREATE",
            "DELETE",
            "DESC",
            "DISTINCT",
            "DROP",
            "ELSE",
            "END",
            "EXISTS",
            "FROM",
            "GROUP",
            "HAVING",
            "IN",
            "INDEX",
            "INNER",
            "INSERT",
            "INTO",
            "IS",
            "JOIN",
            "LEFT",
            "LIMIT",
            "NOT",
            "NULL",
            "OFFSET",
            "ON",
            "OR",
            "ORDER",
            "OUTER",
            "RIGHT",
            "SELECT",
            "SET",
            "TABLE",
            "THEN",
            "UNION",
            "UPDATE",
            "VALUES",
            "VIEW",
            "WHEN",
            "WHERE");

    private static final Set<String> RUST_KEYWORDS = Set.of(
            "as",
            "async",
            "await",
            "break",
            "const",
            "continue",
            "crate",
            "dyn",
            "else",
            "enum",
            "extern",
            "false",
            "fn",
            "for",
            "if",
            "impl",
            "in",
            "let",
            "loop",
            "match",
            "mod",
            "move",
            "mut",
            "pub",
            "ref",
            "return",
            "self",
            "Self",
            "static",
            "struct",
            "super",
            "trait",
            "true",
            "type",
            "unsafe",
            "use",
            "where",
            "while");

    private static final Set<String> CPP_KEYWORDS = Set.of(
            "auto",
            "bool",
            "break",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "constexpr",
            "continue",
            "default",
            "delete",
            "do",
            "double",
            "else",
            "enum",
            "explicit",
            "export",
            "extern",
            "false",
            "float",
            "for",
            "friend",
            "goto",
            "if",
            "inline",
            "int",
            "long",
            "mutable",
            "namespace",
            "new",
            "noexcept",
            "nullptr",
            "operator",
            "private",
            "protected",
            "public",
            "register",
            "reinterpret_cast",
            "return",
            "short",
            "signed",
            "sizeof",
            "static",
            "static_cast",
            "struct",
            "switch",
            "template",
            "this",
            "throw",
            "true",
            "try",
            "typedef",
            "typeid",
            "typename",
            "union",
            "unsigned",
            "using",
            "virtual",
            "void",
            "volatile",
            "while");

    private static final Set<String> COMMON_LITERALS =
            Set.of("true", "false", "null", "nil", "None", "True", "False", "undefined", "NaN", "nullptr");

    private TerminalSyntaxHighlighter() {}

    static String highlight(Tui4jTerminalTheme theme, String language, String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) {
            return "";
        }
        String lang = normalizeLanguage(language);
        if (lang.isEmpty() || "plaintext".equals(lang) || "text".equals(lang)) {
            return theme.codeBlock(rawLine);
        }
        try {
            return scanAndRender(theme, lang, rawLine);
        } catch (Exception ignored) {
            return theme.codeBlock(rawLine);
        }
    }

    static String normalizeLanguage(String lang) {
        if (lang == null || lang.isBlank()) return "";
        String clean = lang.strip().toLowerCase(Locale.ROOT);
        int semi = clean.indexOf(';');
        if (semi > 0) clean = clean.substring(0, semi);
        int colon = clean.indexOf(':');
        if (colon > 0) clean = clean.substring(0, colon);
        return switch (clean) {
            case "java" -> "java";
            case "py", "python", "python3" -> "python";
            case "js", "javascript", "jsx", "mjs", "cjs" -> "javascript";
            case "ts", "typescript", "tsx", "mts", "cts" -> "typescript";
            case "sh", "bash", "zsh", "shell", "ksh" -> "shell";
            case "json", "jsonc" -> "json";
            case "yml", "yaml" -> "yaml";
            case "sql" -> "sql";
            case "go", "golang" -> "go";
            case "md", "markdown" -> "markdown";
            case "rs", "rust" -> "rust";
            case "c", "h", "cpp", "cxx", "cc", "hpp" -> "cpp";
            case "html", "xml", "svg" -> "xml";
            default -> clean;
        };
    }

    private static String scanAndRender(Tui4jTerminalTheme theme, String lang, String line) {
        StringBuilder sb = new StringBuilder(line.length() * 2);
        int i = 0;
        int len = line.length();

        while (i < len) {
            char c = line.charAt(i);

            // Comments
            if (isCommentStart(lang, line, i)) {
                String comment = line.substring(i);
                sb.append(theme.syntaxComment(comment));
                break;
            }

            // Whitespace
            if (Character.isWhitespace(c)) {
                int start = i;
                while (i < len && Character.isWhitespace(line.charAt(i))) {
                    i++;
                }
                sb.append(theme.syntaxPlain(line.substring(start, i)));
                continue;
            }

            // String literals
            if (c == '"' || c == '\'' || (c == '`' && supportsBacktick(lang))) {
                int start = i;
                char quote = c;
                i++;
                boolean escaped = false;
                while (i < len) {
                    char cur = line.charAt(i);
                    if (escaped) {
                        escaped = false;
                    } else if (cur == '\\') {
                        escaped = true;
                    } else if (cur == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
                String str = line.substring(start, i);
                // JSON key check: if string is followed by ':'
                if ("json".equals(lang) && isFollowedByColon(line, i)) {
                    sb.append(theme.syntaxType(str));
                } else {
                    sb.append(theme.syntaxString(str));
                }
                continue;
            }

            // Annotation or Decorator: @ident
            if (c == '@' && (i + 1 < len) && Character.isJavaIdentifierStart(line.charAt(i + 1))) {
                int start = i;
                i++;
                while (i < len && (Character.isJavaIdentifierPart(line.charAt(i)) || line.charAt(i) == '.')) {
                    i++;
                }
                sb.append(theme.syntaxType(line.substring(start, i)));
                continue;
            }

            // Shell variable: $VAR or ${VAR}
            if (c == '$' && "shell".equals(lang) && i + 1 < len) {
                int start = i;
                i++;
                if (line.charAt(i) == '{') {
                    while (i < len && line.charAt(i) != '}') {
                        i++;
                    }
                    if (i < len && line.charAt(i) == '}') i++;
                } else {
                    while (i < len && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) {
                        i++;
                    }
                }
                sb.append(theme.syntaxLiteral(line.substring(start, i)));
                continue;
            }

            // Numeric literals
            if (Character.isDigit(c) || (c == '.' && i + 1 < len && Character.isDigit(line.charAt(i + 1)))) {
                int start = i;
                if (c == '0' && i + 1 < len && (line.charAt(i + 1) == 'x' || line.charAt(i + 1) == 'X')) {
                    i += 2;
                    while (i < len && isHexDigit(line.charAt(i))) {
                        i++;
                    }
                } else {
                    while (i < len
                            && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.' || line.charAt(i) == '_')) {
                        i++;
                    }
                    if (i < len && (line.charAt(i) == 'e' || line.charAt(i) == 'E')) {
                        i++;
                        if (i < len && (line.charAt(i) == '+' || line.charAt(i) == '-')) i++;
                        while (i < len && Character.isDigit(line.charAt(i))) i++;
                    }
                    if (i < len
                            && (line.charAt(i) == 'f'
                                    || line.charAt(i) == 'F'
                                    || line.charAt(i) == 'd'
                                    || line.charAt(i) == 'D'
                                    || line.charAt(i) == 'l'
                                    || line.charAt(i) == 'L'
                                    || line.charAt(i) == 'u'
                                    || line.charAt(i) == 'U')) {
                        i++;
                    }
                }
                sb.append(theme.syntaxNumber(line.substring(start, i)));
                continue;
            }

            // Identifiers / Words
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < len && Character.isJavaIdentifierPart(line.charAt(i))) {
                    i++;
                }
                String word = line.substring(start, i);

                // YAML key check: identifier followed by ':'
                if ("yaml".equals(lang) && isFollowedByColon(line, i)) {
                    sb.append(theme.syntaxType(word));
                } else if (isKeyword(lang, word)) {
                    sb.append(theme.syntaxKeyword(word));
                } else if (COMMON_LITERALS.contains(word)) {
                    sb.append(theme.syntaxLiteral(word));
                } else if (isTypeName(word)) {
                    sb.append(theme.syntaxType(word));
                } else {
                    sb.append(theme.syntaxPlain(word));
                }
                continue;
            }

            // Punctuation / Operators
            if (isPunctuation(c)) {
                sb.append(theme.syntaxPunctuation(String.valueOf(c)));
                i++;
                continue;
            }

            // Default fallback for any other characters
            sb.append(theme.syntaxPlain(String.valueOf(c)));
            i++;
        }

        return sb.toString();
    }

    private static boolean isCommentStart(String lang, String line, int index) {
        if (line.startsWith("//", index)) {
            return "java".equals(lang)
                    || "javascript".equals(lang)
                    || "typescript".equals(lang)
                    || "go".equals(lang)
                    || "cpp".equals(lang)
                    || "rust".equals(lang);
        }
        if (line.startsWith("/*", index)) {
            return "java".equals(lang)
                    || "javascript".equals(lang)
                    || "typescript".equals(lang)
                    || "go".equals(lang)
                    || "cpp".equals(lang)
                    || "rust".equals(lang)
                    || "sql".equals(lang);
        }
        if (line.charAt(index) == '#') {
            return "python".equals(lang) || "shell".equals(lang) || "yaml".equals(lang);
        }
        if (line.startsWith("--", index)) {
            return "sql".equals(lang);
        }
        return false;
    }

    private static boolean supportsBacktick(String lang) {
        return "javascript".equals(lang)
                || "typescript".equals(lang)
                || "go".equals(lang)
                || "shell".equals(lang)
                || "markdown".equals(lang);
    }

    private static boolean isFollowedByColon(String line, int index) {
        int i = index;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i < line.length() && line.charAt(i) == ':';
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isKeyword(String lang, String word) {
        return switch (lang) {
            case "java" -> JAVA_KEYWORDS.contains(word);
            case "python" -> PYTHON_KEYWORDS.contains(word);
            case "javascript", "typescript" -> JS_KEYWORDS.contains(word);
            case "go" -> GO_KEYWORDS.contains(word);
            case "shell" -> SHELL_KEYWORDS.contains(word);
            case "sql" -> SQL_KEYWORDS.contains(word.toUpperCase(Locale.ROOT));
            case "rust" -> RUST_KEYWORDS.contains(word);
            case "cpp" -> CPP_KEYWORDS.contains(word);
            default -> false;
        };
    }

    private static boolean isTypeName(String word) {
        if (word.length() < 2) return false;
        char first = word.charAt(0);
        return Character.isUpperCase(first) && word.chars().anyMatch(Character::isLowerCase);
    }

    private static boolean isPunctuation(char c) {
        return switch (c) {
            case '{',
                    '}',
                    '[',
                    ']',
                    '(',
                    ')',
                    ';',
                    ',',
                    '.',
                    ':',
                    '=',
                    '+',
                    '-',
                    '*',
                    '/',
                    '%',
                    '&',
                    '|',
                    '^',
                    '!',
                    '<',
                    '>',
                    '?',
                    '~' -> true;
            default -> false;
        };
    }
}
