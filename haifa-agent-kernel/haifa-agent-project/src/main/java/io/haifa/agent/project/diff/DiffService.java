package io.haifa.agent.project.diff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class DiffService {
    public DiffResult generate(DiffRequest request) {
        if (request.files().size() > request.maxFiles()) {
            throw new IllegalArgumentException("diff file budget exceeded");
        }
        StringBuilder output = new StringBuilder();
        List<DiffFile> ordered = request.files().stream()
                .sorted(Comparator.comparing(DiffFile::path))
                .toList();
        for (DiffFile file : ordered) {
            enforceFileBudget(file.before(), request.maxFileBytes());
            enforceFileBudget(file.after(), request.maxFileBytes());
            appendFile(output, file);
            if (output.toString().getBytes(StandardCharsets.UTF_8).length > request.maxOutputBytes()) {
                throw new IllegalArgumentException("diff output budget exceeded");
            }
        }
        byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
        return new DiffResult(output.toString(), ordered.size(), bytes.length, "sha256:" + hash(bytes));
    }

    private static void appendFile(StringBuilder output, DiffFile file) {
        String oldPath =
                file.before() == null ? "/dev/null" : "a/" + file.path().value();
        String newPath = file.after() == null ? "/dev/null" : "b/" + file.path().value();
        List<String> before = lines(file.before());
        List<String> after = lines(file.after());
        output.append("--- ").append(oldPath).append('\n');
        output.append("+++ ").append(newPath).append('\n');
        output.append("@@ -")
                .append(before.isEmpty() ? 0 : 1)
                .append(',')
                .append(before.size())
                .append(" +")
                .append(after.isEmpty() ? 0 : 1)
                .append(',')
                .append(after.size())
                .append(" @@\n");
        before.forEach(line -> output.append('-').append(line).append('\n'));
        if (file.before() != null && !file.before().isEmpty() && !endsWithNewline(file.before())) {
            output.append("\\ No newline at end of file\n");
        }
        after.forEach(line -> output.append('+').append(line).append('\n'));
        if (file.after() != null && !file.after().isEmpty() && !endsWithNewline(file.after())) {
            output.append("\\ No newline at end of file\n");
        }
    }

    private static List<String> lines(String value) {
        if (value == null || value.isEmpty()) return List.of();
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        String[] values = normalized.split("\n", -1);
        int length = endsWithNewline(value) ? values.length - 1 : values.length;
        return java.util.Arrays.asList(values).subList(0, length);
    }

    private static boolean endsWithNewline(String value) {
        return value.endsWith("\n") || value.endsWith("\r");
    }

    private static void enforceFileBudget(String value, int maximum) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IllegalArgumentException("diff file budget exceeded");
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
