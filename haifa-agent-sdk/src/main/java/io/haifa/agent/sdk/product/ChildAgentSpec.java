package io.haifa.agent.sdk.product;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Trusted definition of one child agent a parent run may delegate to through the Runtime {@code task} Tool.
 *
 * <p>{@code description} tells the parent model when to choose this child. {@code instructions} are the child's
 * own instructions. {@code runProfile}, when present, selects the registered run profile whose model, budget and
 * limits the child uses; when absent the child inherits the frozen model, budget and limits of its parent run.
 * {@code allowedTools} is the child's Tool allowlist; the child's effective Tools are this set intersected with
 * the Tools its parent may use, so a read-only child is expressed by listing only read-only Tools. A child never
 * delegates further and neither recalls nor writes long-term Memory.
 */
public record ChildAgentSpec(
        String id,
        String description,
        String instructions,
        Optional<ProductRunProfileRef> runProfile,
        Set<String> allowedTools) {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    public ChildAgentSpec {
        id = ProductValues.text(id, "id", 64);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("child agent id must match " + ID.pattern());
        }
        description = ProductValues.text(description, "description", 1_000);
        instructions = ProductValues.text(instructions, "instructions", 32_000);
        runProfile = Objects.requireNonNull(runProfile, "runProfile must not be null");
        allowedTools = Objects.requireNonNull(allowedTools, "allowedTools must not be null").stream()
                .map(alias -> ProductValues.text(alias, "allowedTools entry", 128))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** A child that inherits its parent's model, budget and limits. */
    public static ChildAgentSpec of(String id, String description, String instructions, Set<String> allowedTools) {
        return new ChildAgentSpec(id, description, instructions, Optional.empty(), allowedTools);
    }
}
