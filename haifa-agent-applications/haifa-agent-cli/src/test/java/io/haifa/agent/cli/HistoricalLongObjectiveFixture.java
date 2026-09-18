package io.haifa.agent.cli;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class HistoricalLongObjectiveFixture {
    static final int TARGET_LENGTH = 24_512;
    static final String BEGIN_MARKER = "HAIFA_PYLINT_INPUT_BEGIN_7080";
    static final String MIDDLE_MARKER = "HAIFA_PYLINT_INPUT_MIDDLE_7080";
    static final String END_MARKER = "HAIFA_PYLINT_INPUT_END_7080";

    private HistoricalLongObjectiveFixture() {}

    static String create() {
        StringBuilder value = new StringBuilder(TARGET_LENGTH);
        value.append(BEGIN_MARKER)
                .append('\n')
                .append("Task: inspect a public Pylint regression without discarding diagnostic evidence.\n")
                .append("unicode-note=路径/naïve/Δ\n");
        appendDiagnosticsUntil(value, TARGET_LENGTH / 2);
        value.append('\n').append(MIDDLE_MARKER).append('\n');

        int bodyEnd = TARGET_LENGTH - END_MARKER.length() - 1;
        appendDiagnosticsUntil(value, bodyEnd);
        value.setLength(bodyEnd);
        value.append('\n').append(END_MARKER);

        if (value.length() != TARGET_LENGTH) {
            throw new IllegalStateException("historical fixture length drifted: " + value.length());
        }
        return value.toString();
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void appendDiagnosticsUntil(StringBuilder value, int targetLength) {
        int index = 0;
        while (value.length() < targetLength) {
            int line = 20 + index % 170;
            int column = 1 + index % 23;
            String code =
                    switch (index % 5) {
                        case 0 -> "C0114";
                        case 1 -> "W0611";
                        case 2 -> "R0913";
                        case 3 -> "E1120";
                        default -> "W1514";
                    };
            value.append("tests/functional/")
                    .append(index % 19)
                    .append("/case_")
                    .append(index % 37)
                    .append(".py:")
                    .append(line)
                    .append(':')
                    .append(column)
                    .append(": ")
                    .append(code)
                    .append(": synthetic varied diagnostic for symbol_")
                    .append(index)
                    .append(" (fixture-checker)\n");
            index++;
        }
        value.setLength(targetLength);
    }
}
