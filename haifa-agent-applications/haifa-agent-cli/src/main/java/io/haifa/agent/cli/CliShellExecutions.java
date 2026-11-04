package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.CodingShellExecution;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.tool.api.ToolCancellation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/** Bounded, Host-owned manual Shell receipts; unrelated to AgentRun lifecycle or durable recovery. */
final class CliShellExecutions implements AutoCloseable {
    private static final int MAX_ACTIVE = 8;
    private static final int MAX_RETAINED = 128;
    private static final long RETENTION_MILLIS = 30 * 60 * 1000L;
    private final TimeProvider time;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private boolean closed;

    record Work(AgentSessionId sessionId, Function<ToolCancellation, CodingShellResult> execute) {}

    CliShellExecutions(TimeProvider time) { this.time = time; }

    synchronized CodingShellExecution start(String id, Supplier<Work> prepare) {
        if (closed) throw unavailable();
        prune();
        Entry existing = entries.get(id);
        if (existing != null) return existing.view();
        // Admission precedes consuming a one-shot plan. Busy callers can safely try that plan later.
        if (entries.values().stream().filter(e -> !e.done.isDone()).count() >= MAX_ACTIVE) {
            throw new ProjectProductException("SHELL_BUSY", "Too many active Shell commands");
        }
        makeRoom();
        Work work = prepare.get();
        Entry entry = new Entry(id, work.sessionId(), time.now().toEpochMilli());
        entries.put(id, entry);
        workers.execute(() -> {
            try {
                CodingShellResult result = work.execute().apply(entry.cancelled::get);
                synchronized (this) {
                    entry.result = result;
                    entry.state = CodingShellExecution.State.COMPLETED;
                    entry.finishedAt = time.now().toEpochMilli();
                    entry.done.complete(result);
                }
            } catch (RuntimeException failure) {
                synchronized (this) {
                    // Never expose exception text or assume that a failed response undid side effects.
                    entry.error = "SHELL_OUTCOME_UNKNOWN";
                    entry.state = CodingShellExecution.State.FAILED;
                    entry.finishedAt = time.now().toEpochMilli();
                    entry.done.completeExceptionally(new ProjectProductException(
                            entry.error, "Shell completion could not be confirmed"));
                }
            }
        });
        return entry.view();
    }

    CodingShellResult await(String id) {
        CompletableFuture<CodingShellResult> done;
        synchronized (this) { done = require(id).done; }
        try { return done.get(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProjectProductException("SHELL_OUTCOME_UNKNOWN", "Shell may still be running");
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new ProjectProductException("SHELL_OUTCOME_UNKNOWN", "Shell completion could not be confirmed");
        }
    }

    synchronized CodingShellExecution get(AgentSessionId session, String id) {
        prune();
        Entry entry = require(id);
        if (!entry.session.equals(session)) throw unavailable();
        return entry.view();
    }

    synchronized List<CodingShellExecution> list(AgentSessionId session) {
        prune();
        List<CodingShellExecution> views = new ArrayList<>();
        for (Entry entry : entries.values()) if (entry.session.equals(session)) views.add(entry.view());
        java.util.Collections.reverse(views);
        return List.copyOf(views);
    }

    synchronized CodingShellExecution cancel(AgentSessionId session, String id, Supplier<Work> pending) {
        if (closed) throw unavailable();
        prune();
        Entry entry = entries.get(id);
        if (entry == null) {
            makeRoom();
            Work work = pending.get();
            if (!work.sessionId().equals(session)) throw unavailable();
            entry = new Entry(id, session, time.now().toEpochMilli());
            entry.result = new CodingShellResult("CANCELLED", Optional.empty(),
                    "Shell cancelled before dispatch", Optional.empty(), false, false);
            entry.state = CodingShellExecution.State.COMPLETED;
            entry.finishedAt = time.now().toEpochMilli();
            entry.done.complete(entry.result);
            entries.put(id, entry);
        }
        if (!entry.session.equals(session)) throw unavailable();
        if (!entry.done.isDone() || "CANCELLED".equals(entry.result == null ? "" : entry.result.processState())) {
            entry.cancelled.set(true);
        }
        return entry.view();
    }

    private Entry require(String id) {
        Entry entry = entries.get(id);
        if (entry == null) throw unavailable();
        return entry;
    }

    private void prune() {
        long now = time.now().toEpochMilli();
        entries.values().removeIf(e -> e.done.isDone() && now - e.finishedAt >= RETENTION_MILLIS);
    }

    private void makeRoom() {
        if (entries.size() < MAX_RETAINED) return;
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().done.isDone()) { iterator.remove(); return; }
        }
        throw new ProjectProductException("SHELL_BUSY", "Shell receipt capacity reached");
    }

    private static ProjectProductException unavailable() {
        return new ProjectProductException("SHELL_EXECUTION_UNAVAILABLE", "Shell receipt is unavailable or expired");
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            entries.values().forEach(e -> { if (!e.done.isDone()) e.cancelled.set(true); });
            workers.shutdown();
        }
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
                throw new IllegalStateException("Shell commands have not settled");
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Shell shutdown interrupted");
        }
    }

    private static final class Entry {
        final String id;
        final AgentSessionId session;
        final long acceptedAt;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CompletableFuture<CodingShellResult> done = new CompletableFuture<>();
        CodingShellExecution.State state = CodingShellExecution.State.RUNNING;
        CodingShellResult result;
        String error;
        long finishedAt;
        Entry(String id, AgentSessionId session, long acceptedAt) {
            this.id = id; this.session = session; this.acceptedAt = acceptedAt;
        }
        CodingShellExecution view() {
            return new CodingShellExecution(id, session, acceptedAt, state, cancelled.get(),
                    Optional.ofNullable(result), Optional.ofNullable(error));
        }
    }
}
