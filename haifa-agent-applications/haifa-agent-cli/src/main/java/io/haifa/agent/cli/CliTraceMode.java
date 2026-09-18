package io.haifa.agent.cli;

import java.util.Locale;

enum CliTraceMode {
    SUMMARY,
    DETAIL,
    JSONL;

    static CliTraceMode parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("trace must be summary, detail, or jsonl");
        }
    }
}
