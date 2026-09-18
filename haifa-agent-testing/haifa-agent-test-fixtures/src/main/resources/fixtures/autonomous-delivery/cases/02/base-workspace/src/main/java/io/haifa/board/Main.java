package io.haifa.board;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
        }
        Path database = Path.of(args[0]);
        String command = args[1];
        TaskStore store = new TaskStore(database);
        switch (command) {
            case "add" -> add(store, args);
            case "done" -> done(store, args);
            case "list" -> list(store, args);
            default -> usage();
        }
    }

    private static void add(TaskStore store, String[] args) throws Exception {
        if (args.length != 5) {
            usage();
        }
        List<Task> tasks = store.load();
        long id = tasks.stream().mapToLong(Task::id).max().orElse(0) + 1;
        Task.Priority priority = Task.Priority.valueOf(args[3].toUpperCase());
        LocalDate due = args[4].equals("-") ? null : LocalDate.parse(args[4]);
        tasks.add(new Task(id, args[2], priority, due, Task.Status.OPEN));
        store.save(tasks);
        System.out.println(id);
    }

    private static void done(TaskStore store, String[] args) throws Exception {
        if (args.length != 3) {
            usage();
        }
        long id = Long.parseLong(args[2]);
        List<Task> tasks = new ArrayList<>(store.load());
        for (int index = 0; index < tasks.size(); index++) {
            if (tasks.get(index).id() == id) {
                tasks.set(index, tasks.get(index).markDone());
                store.save(tasks);
                System.out.println("done " + id);
                return;
            }
        }
        throw new IllegalArgumentException("unknown task: " + id);
    }

    private static void list(TaskStore store, String[] args) throws Exception {
        if (args.length != 2) {
            usage();
        }
        store.load().stream()
                .sorted(Comparator.comparingLong(Task::id))
                .forEach(
                        task ->
                                System.out.printf(
                                        "%d\t%s\t%s\t%s\t%s%n",
                                        task.id(),
                                        task.status(),
                                        task.priority(),
                                        task.due() == null ? "-" : task.due(),
                                        task.title()));
    }

    private static void usage() {
        System.err.println("usage: task-board DB_FILE COMMAND [ARGS]");
        System.exit(2);
    }
}
