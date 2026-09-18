package io.haifa.agent.store.sqlite.migration;

import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** SQL statement parser that preserves semicolons inside quotes, comments and trigger bodies. */
public final class SqlScriptParser {
    private SqlScriptParser() {}

    public static List<String> parse(String script) {
        if (script == null) {
            throw invalid("migration script must not be null");
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        StringBuilder token = new StringBuilder();
        List<String> leadingTokens = new ArrayList<>();
        Deque<String> triggerBlocks = new ArrayDeque<>();
        State state = State.NORMAL;
        boolean trigger = false;

        for (int index = 0; index < script.length(); index++) {
            char character = script.charAt(index);
            char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';
            switch (state) {
                case SINGLE_QUOTE -> {
                    current.append(character);
                    if (character == '\'' && next == '\'') {
                        current.append(next);
                        index++;
                    } else if (character == '\'') {
                        state = State.NORMAL;
                    }
                }
                case DOUBLE_QUOTE -> {
                    current.append(character);
                    if (character == '"' && next == '"') {
                        current.append(next);
                        index++;
                    } else if (character == '"') {
                        state = State.NORMAL;
                    }
                }
                case LINE_COMMENT -> {
                    if (character == '\n' || character == '\r') {
                        current.append(' ');
                        state = State.NORMAL;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (character == '*' && next == '/') {
                        current.append(' ');
                        index++;
                        state = State.NORMAL;
                    }
                }
                case NORMAL -> {
                    if (character == '-' && next == '-') {
                        flushToken(token, leadingTokens, triggerBlocks, trigger);
                        state = State.LINE_COMMENT;
                        index++;
                    } else if (character == '/' && next == '*') {
                        flushToken(token, leadingTokens, triggerBlocks, trigger);
                        state = State.BLOCK_COMMENT;
                        index++;
                    } else if (character == '\'') {
                        flushToken(token, leadingTokens, triggerBlocks, trigger);
                        current.append(character);
                        state = State.SINGLE_QUOTE;
                    } else if (character == '"') {
                        flushToken(token, leadingTokens, triggerBlocks, trigger);
                        current.append(character);
                        state = State.DOUBLE_QUOTE;
                    } else if (Character.isLetterOrDigit(character) || character == '_') {
                        token.append(character);
                        current.append(character);
                    } else {
                        String completedToken = flushToken(token, leadingTokens, triggerBlocks, trigger);
                        if (!trigger && isCreateTrigger(leadingTokens)) {
                            trigger = true;
                            triggerBlocks.clear();
                            for (String value : leadingTokens) {
                                updateTriggerBlocks(triggerBlocks, value);
                            }
                        } else if (trigger && completedToken != null) {
                            updateTriggerBlocks(triggerBlocks, completedToken);
                        }
                        if (character == ';' && (!trigger || triggerBlocks.isEmpty())) {
                            addStatement(statements, current);
                            leadingTokens.clear();
                            triggerBlocks.clear();
                            trigger = false;
                        } else {
                            current.append(character);
                        }
                    }
                }
            }
        }

        if (state == State.SINGLE_QUOTE || state == State.DOUBLE_QUOTE || state == State.BLOCK_COMMENT) {
            throw invalid("migration script contains an unterminated quote or block comment");
        }
        String completedToken = flushToken(token, leadingTokens, triggerBlocks, trigger);
        if (trigger && completedToken != null) {
            updateTriggerBlocks(triggerBlocks, completedToken);
        }
        if (trigger && !triggerBlocks.isEmpty()) {
            throw invalid("migration script contains an unterminated trigger body");
        }
        addStatement(statements, current);
        return List.copyOf(statements);
    }

    private static String flushToken(
            StringBuilder token, List<String> leadingTokens, Deque<String> triggerBlocks, boolean trigger) {
        if (token.isEmpty()) {
            return null;
        }
        String value = token.toString().toUpperCase(Locale.ROOT);
        token.setLength(0);
        if (leadingTokens.size() < 4) {
            leadingTokens.add(value);
        }
        return value;
    }

    private static boolean isCreateTrigger(List<String> tokens) {
        if (tokens.size() < 2 || !"CREATE".equals(tokens.get(0))) {
            return false;
        }
        int position = 1;
        if ("TEMP".equals(tokens.get(position)) || "TEMPORARY".equals(tokens.get(position))) {
            position++;
        }
        return position < tokens.size() && "TRIGGER".equals(tokens.get(position));
    }

    private static void updateTriggerBlocks(Deque<String> blocks, String token) {
        if ("BEGIN".equals(token) || "CASE".equals(token)) {
            blocks.push(token);
        } else if ("END".equals(token) && !blocks.isEmpty()) {
            blocks.pop();
        }
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String value = current.toString().trim();
        current.setLength(0);
        if (!value.isEmpty()) {
            statements.add(value);
        }
    }

    private static SqliteStoreException invalid(String message) {
        return new SqliteStoreException(SqliteStoreFailure.MIGRATION_FAILED, message);
    }

    private enum State {
        NORMAL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        LINE_COMMENT,
        BLOCK_COMMENT
    }
}
