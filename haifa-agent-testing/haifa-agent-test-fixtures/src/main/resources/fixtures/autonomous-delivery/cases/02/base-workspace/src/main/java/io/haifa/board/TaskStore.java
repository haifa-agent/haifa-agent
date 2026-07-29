package io.haifa.board;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class TaskStore {
    private final Path path;

    TaskStore(Path path) {
        this.path = path;
    }

    List<Task> load() throws IOException {
        if (Files.notExists(path)) {
            return new ArrayList<>();
        }
        List<Task> tasks = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            tasks.add(
                    new Task(
                            Long.parseLong(fields[0]),
                            fields[4],
                            Task.Priority.valueOf(fields[2]),
                            fields[3].equals("-") ? null : LocalDate.parse(fields[3]),
                            Task.Status.valueOf(fields[1])));
        }
        tasks.sort(Comparator.comparingLong(Task::id));
        return tasks;
    }

    void save(List<Task> tasks) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines =
                tasks.stream()
                        .sorted(Comparator.comparingLong(Task::id))
                        .map(
                                task ->
                                        "%d\t%s\t%s\t%s\t%s"
                                                .formatted(
                                                        task.id(),
                                                        task.status(),
                                                        task.priority(),
                                                        task.due() == null ? "-" : task.due(),
                                                        task.title()))
                        .toList();
        Files.write(path, lines, StandardCharsets.UTF_8);
    }
}
