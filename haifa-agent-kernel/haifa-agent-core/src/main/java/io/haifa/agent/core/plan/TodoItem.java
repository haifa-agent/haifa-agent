package io.haifa.agent.core.plan;

import static io.haifa.agent.core.support.DomainValues.requireText;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Dependency-aware unit of work within an Agent plan. */
public final class TodoItem {

    private final TodoItemId id;
    private final String title;
    private final String description;
    private final TodoPriority priority;
    private final List<TodoItemId> dependencies;
    private TodoStatus status = TodoStatus.PENDING;
    private String completionSummary;
    private Instant startedAt;
    private Instant completedAt;

    public TodoItem(
            TodoItemId id, String title, String description, TodoPriority priority, List<TodoItemId> dependencies) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.title = requireText(title, "title");
        this.description = requireText(description, "description");
        this.priority = Objects.requireNonNull(priority, "priority must not be null");
        this.dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies must not be null"));
        if (this.dependencies.contains(id)) {
            throw new IllegalArgumentException("todo item cannot depend on itself");
        }
        if (Set.copyOf(this.dependencies).size() != this.dependencies.size()) {
            throw new IllegalArgumentException("todo dependencies must be unique");
        }
    }

    public void start(Set<TodoItemId> completedDependencies, Instant at) {
        if (status != TodoStatus.PENDING && status != TodoStatus.BLOCKED) {
            throw new IllegalStateException("only pending or blocked todo can start");
        }
        Objects.requireNonNull(completedDependencies, "completedDependencies must not be null");
        if (!completedDependencies.containsAll(dependencies)) {
            throw new IllegalStateException("todo dependencies are not completed");
        }
        startedAt = Objects.requireNonNull(at, "at must not be null");
        status = TodoStatus.IN_PROGRESS;
    }

    public void block() {
        requireStatus(TodoStatus.IN_PROGRESS);
        status = TodoStatus.BLOCKED;
    }

    public void complete(String summary, Instant at) {
        requireStatus(TodoStatus.IN_PROGRESS);
        completionSummary = requireText(summary, "completionSummary");
        completedAt = requireCompletionTime(at);
        status = TodoStatus.COMPLETED;
    }

    public void cancel(Instant at) {
        finishWithoutSummary(TodoStatus.CANCELLED, at);
    }

    public void skip(Instant at) {
        finishWithoutSummary(TodoStatus.SKIPPED, at);
    }

    private void finishWithoutSummary(TodoStatus target, Instant at) {
        if (status == TodoStatus.COMPLETED || status == TodoStatus.CANCELLED || status == TodoStatus.SKIPPED) {
            throw new IllegalStateException("terminal todo cannot be changed");
        }
        completedAt = requireCompletionTime(at);
        status = target;
    }

    private Instant requireCompletionTime(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (startedAt != null && at.isBefore(startedAt)) {
            throw new IllegalArgumentException("todo completion time must not precede start time");
        }
        return at;
    }

    private void requireStatus(TodoStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("expected todo status " + expected + " but was " + status);
        }
    }

    public TodoItemId id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public TodoPriority priority() {
        return priority;
    }

    public List<TodoItemId> dependencies() {
        return dependencies;
    }

    public TodoStatus status() {
        return status;
    }

    public Optional<String> completionSummary() {
        return Optional.ofNullable(completionSummary);
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }
}
