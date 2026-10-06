package io.haifa.agent.sdk.internal;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.plan.AgentPlanId;
import io.haifa.agent.core.plan.TodoItem;
import io.haifa.agent.core.plan.TodoItemId;
import io.haifa.agent.core.plan.TodoPriority;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.sdk.plan.PlanAuthoringInput;
import io.haifa.agent.sdk.plan.PlanAuthoringOutput;
import io.haifa.agent.sdk.plan.PlanAuthoringSpec;
import io.haifa.agent.sdk.plan.PlanTodoItemInput;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * In-process tool that allows the model to create and revise the Run plan.
 */
public final class PlanAuthoringTool implements JavaTool<PlanAuthoringInput, PlanAuthoringOutput> {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_COMPLETED = "completed";
    public static final String DEFAULT_OBJECTIVE = "Execution plan";

    private final PlanAuthoringSpec spec;
    private final RuntimeStateRepository state;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final JavaToolSpec<PlanAuthoringInput, PlanAuthoringOutput> toolSpec;

    public PlanAuthoringTool(
            PlanAuthoringSpec spec,
            RuntimeStateRepository state,
            IdentifierGenerator ids,
            TimeProvider time) {
        this.spec = Objects.requireNonNull(spec, "spec must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.toolSpec = JavaToolSpec.builder(spec.toolName(), PlanAuthoringInput.class, PlanAuthoringOutput.class)
                .title(spec.toolName())
                .description(spec.toolDescription())
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public JavaToolSpec<PlanAuthoringInput, PlanAuthoringOutput> spec() {
        return toolSpec;
    }

    @Override
    public PlanAuthoringOutput invoke(PlanAuthoringInput input, JavaToolContext context) {
        Objects.requireNonNull(context, "context must not be null");

        Optional<AgentPlan> existingPlan = state.plan(context.runId());
        long currentRevision = existingPlan.map(AgentPlan::revision).orElse(0L);
        int currentItemCount = existingPlan.map(p -> p.items().size()).orElse(0);

        if (input == null || input.todos() == null) {
            return new PlanAuthoringOutput(
                    currentRevision,
                    currentItemCount,
                    "Error: missing required field 'todos'");
        }

        List<PlanTodoItemInput> todoInputs = input.todos();
        for (int i = 0; i < todoInputs.size(); i++) {
            PlanTodoItemInput item = todoInputs.get(i);
            if (item == null) {
                return new PlanAuthoringOutput(
                        currentRevision,
                        currentItemCount,
                        "Error: todo item at index " + i + " must not be null");
            }
            if (item.content() == null || item.content().trim().isEmpty()) {
                return new PlanAuthoringOutput(
                        currentRevision,
                        currentItemCount,
                        "Error: todo item content at index " + i + " must not be blank");
            }
            if (item.status() == null) {
                return new PlanAuthoringOutput(
                        currentRevision,
                        currentItemCount,
                        "Error: todo item status at index " + i + " must not be null");
            }
            String status = item.status();
            if (!STATUS_PENDING.equals(status) && !STATUS_IN_PROGRESS.equals(status) && !STATUS_COMPLETED.equals(status)) {
                return new PlanAuthoringOutput(
                        currentRevision,
                        currentItemCount,
                        "Error: invalid todo item status at index " + i + ": '" + status
                                + "'. Allowed statuses are: pending, in_progress, completed");
            }
        }

        Instant now = time.now();
        List<TodoItem> items = new ArrayList<>();
        for (PlanTodoItemInput itemInput : todoInputs) {
            String title = itemInput.content().trim();
            TodoItemId itemId = new TodoItemId(ids.nextValue());
            TodoItem todoItem = new TodoItem(itemId, title, title, TodoPriority.NORMAL, List.of());
            String status = itemInput.status();
            if (STATUS_IN_PROGRESS.equals(status)) {
                todoItem.start(Set.of(), now);
            } else if (STATUS_COMPLETED.equals(status)) {
                todoItem.start(Set.of(), now);
                todoItem.complete(title, now);
            }
            items.add(todoItem);
        }

        AgentPlan plan;
        if (existingPlan.isPresent()) {
            plan = existingPlan.get();
            Instant at = now.isBefore(plan.updatedAt()) ? plan.updatedAt() : now;
            plan.revise(plan.objective(), items, at);
        } else {
            AgentPlanId planId = new AgentPlanId(ids.nextValue());
            plan = new AgentPlan(planId, context.runId(), DEFAULT_OBJECTIVE, items, now);
        }
        state.savePlan(plan);

        return new PlanAuthoringOutput(
                plan.revision(),
                items.size(),
                "Plan updated with " + items.size() + " tasks (revision " + plan.revision() + ")");
    }

    @Override
    public String summarize(PlanAuthoringOutput output) {
        return output.message();
    }
}
