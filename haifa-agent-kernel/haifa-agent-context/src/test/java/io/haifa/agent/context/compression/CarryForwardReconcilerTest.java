package io.haifa.agent.context.compression;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.message.AgentMessageId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CarryForwardReconcilerTest {

    private static final ProjectedCompactionSource EMPTY_SOURCE =
            new ProjectedCompactionSource("", Map.of(), Map.of(), List.of(), List.of(), Set.of());

    private static ProjectedCompactionSource sourceWithAliases(Map<String, AgentMessageId> aliases) {
        return new ProjectedCompactionSource("", aliases, Map.of(), List.of(), List.of(), Set.of());
    }

    private static SemanticSummaryItem observed(String id, String text, String... refs) {
        return new SemanticSummaryItem(id, text, List.of(refs), SemanticConfidence.OBSERVED);
    }

    private static SemanticSummaryItem inferred(String id, String text, String... refs) {
        return new SemanticSummaryItem(id, text, List.of(refs), SemanticConfidence.INFERRED);
    }

    private static SemanticConversationSummaryV1 summary(
            List<SemanticSummaryItem> goals,
            List<SemanticSummaryItem> constraints,
            SemanticProgress progress,
            List<SemanticDecisionItem> decisions,
            List<SemanticSummaryItem> questions) {
        return new SemanticConversationSummaryV1(
                "v1", "en", goals, constraints, progress, decisions, List.of(), List.of(), questions);
    }

    private static SemanticConversationSummaryV1 emptySummary() {
        return summary(List.of(), List.of(), SemanticProgress.empty(), List.of(), List.of());
    }

    private static SemanticConversationSummaryV1 withGoals(SemanticSummaryItem... items) {
        return summary(List.of(items), List.of(), SemanticProgress.empty(), List.of(), List.of());
    }

    private static SemanticConversationSummaryV1 withConstraints(SemanticSummaryItem... items) {
        return summary(List.of(), List.of(items), SemanticProgress.empty(), List.of(), List.of());
    }

    private static SemanticConversationSummaryV1 withDecisions(SemanticDecisionItem... decisions) {
        return summary(List.of(), List.of(), SemanticProgress.empty(), List.of(decisions), List.of());
    }

    private static SemanticConversationSummaryV1 withCompleted(SemanticSummaryItem... items) {
        return summary(
                List.of(), List.of(), new SemanticProgress(List.of(items), List.of(), List.of()), List.of(), List.of());
    }

    @Test
    @DisplayName("Overwrites candidate text with original carry-forward text verbatim")
    void overwritesModifiedTextWithVerbatimCarryText() {
        SemanticSummaryItem carry = observed("C-1", "Original strict rule: read-only mode required", "m001");
        SemanticSummaryItem candidateItem = observed("C-1", "LLM paraphrased text: must be read-only", "m001");

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(withConstraints(candidateItem), List.of(carry), EMPTY_SOURCE);

        assertThat(reconciled.constraints()).singleElement().satisfies(item -> {
            assertThat(item.stableItemId()).isEqualTo("C-1");
            assertThat(item.text()).isEqualTo("Original strict rule: read-only mode required");
        });
    }

    @Test
    @DisplayName("Merges supporting sourceRefs from carry and candidate without loss")
    void mergesSourceRefs() {
        SemanticSummaryItem carry = observed("C-1", "Rule A", "m001", "m002");
        SemanticSummaryItem candidateItem = observed("C-1", "Rule A paraphrased", "m002", "m003");

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(withConstraints(candidateItem), List.of(carry), EMPTY_SOURCE);

        assertThat(reconciled.constraints().get(0).sourceRefs()).containsExactly("m001", "m002", "m003");
    }

    @Test
    @DisplayName("Restores missing mandatory carry-forward item if not resolved by terminal decision")
    void restoresMissingUnresolvedItem() {
        SemanticSummaryItem carry = observed("Q-1", "Open question about auth scope", "m001");

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(emptySummary(), List.of(carry), EMPTY_SOURCE);

        assertThat(reconciled.unresolvedQuestions()).containsExactly(carry);
    }

    @Test
    @DisplayName("Allows dropping missing carry item when resolved by accepted decision citing current evidence")
    void allowsDroppingResolvedItem() {
        SemanticSummaryItem carry = observed("Q-1", "Open question about auth scope", "m001");
        SemanticDecisionItem resolvingDecision = new SemanticDecisionItem(
                "D-1",
                "Resolved Q-1: use read scope",
                "Decided based on user prompt",
                SemanticDecisionStatus.ACCEPTED,
                List.of("m002"));

        SemanticConversationSummaryV1 reconciled = CarryForwardReconciler.reconcile(
                withDecisions(resolvingDecision),
                List.of(carry),
                sourceWithAliases(Map.of("m002", new AgentMessageId("msg-2"))));

        assertThat(reconciled.unresolvedQuestions()).isEmpty();
    }

    @Test
    @DisplayName("Does not drop missing carry item if decision is PROPOSED")
    void doesNotDropIfDecisionIsProposed() {
        SemanticSummaryItem carry = observed("Q-1", "Open question about auth scope", "m001");
        SemanticDecisionItem proposedDecision = new SemanticDecisionItem(
                "D-1",
                "Proposed resolution for Q-1",
                "Need confirmation",
                SemanticDecisionStatus.PROPOSED,
                List.of("m002"));

        SemanticConversationSummaryV1 reconciled = CarryForwardReconciler.reconcile(
                withDecisions(proposedDecision),
                List.of(carry),
                sourceWithAliases(Map.of("m002", new AgentMessageId("msg-2"))));

        assertThat(reconciled.unresolvedQuestions()).containsExactly(carry);
    }

    @Test
    @DisplayName("Atomically moves misplaced constraint from goals to constraints without duplication")
    void movesMisplacedConstraintBackToConstraints() {
        SemanticSummaryItem carry = observed("C-1", "Strict constraint", "m001");
        SemanticSummaryItem misplacedItem = observed("C-1", "Paraphrased strict constraint", "m001");

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(withGoals(misplacedItem), List.of(carry), EMPTY_SOURCE);

        assertThat(reconciled.goals()).isEmpty();
        assertThat(reconciled.constraints()).singleElement().satisfies(item -> {
            assertThat(item.stableItemId()).isEqualTo("C-1");
            assertThat(item.text()).isEqualTo("Strict constraint");
        });
    }

    @Test
    @DisplayName("Supports lifecycle transition of active item to completed with OBSERVED confidence")
    void supportsProgressLifecycleTransitionToCompleted() {
        SemanticSummaryItem carry = inferred("PA-1", "Compile and run unit tests", "m001");
        SemanticSummaryItem candidateCompleted = inferred("PA-1", "Compile and run unit tests (done)", "m002");

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(withCompleted(candidateCompleted), List.of(carry), EMPTY_SOURCE);

        assertThat(reconciled.progress().active()).isEmpty();
        assertThat(reconciled.progress().completed()).singleElement().satisfies(item -> {
            assertThat(item.stableItemId()).isEqualTo("PA-1");
            assertThat(item.text()).isEqualTo("Compile and run unit tests");
            assertThat(item.confidence()).isEqualTo(SemanticConfidence.OBSERVED);
            assertThat(item.sourceRefs()).containsExactly("m001", "m002");
        });
    }

    @Test
    @DisplayName("Deduplicates duplicate items output by model with same carry ID")
    void deduplicatesDuplicateItems() {
        SemanticSummaryItem carry = observed("C-1", "Constraint verbatim", "m001");
        SemanticSummaryItem dupInGoals = observed("C-1", "Dup 1", "m002");
        SemanticSummaryItem dupInConstraints = observed("C-1", "Dup 2", "m003");

        SemanticConversationSummaryV1 candidate =
                summary(List.of(dupInGoals), List.of(dupInConstraints), SemanticProgress.empty(), List.of(), List.of());

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(candidate, List.of(carry), EMPTY_SOURCE);

        assertThat(reconciled.goals()).isEmpty();
        assertThat(reconciled.constraints()).singleElement().satisfies(item -> {
            assertThat(item.stableItemId()).isEqualTo("C-1");
            assertThat(item.text()).isEqualTo("Constraint verbatim");
            assertThat(item.sourceRefs()).containsExactly("m001", "m003", "m002");
        });
    }

    @Test
    @DisplayName(
            "Identity is the stableItemId: a relocated and re-identified carry item is restored without a duplicate id")
    void relocatedAndReidentifiedCarryItemIsRestoredWithoutDuplicateId() {
        SemanticSummaryItem carry = observed("C-1", "Original strict rule: read-only mode required", "m001");
        // The model both moved the constraint content into goals and re-identified it as G-9, omitting "C-1".
        SemanticSummaryItem renamedCopy = observed("G-9", "Original strict rule: read-only mode required", "m001");

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(withGoals(renamedCopy), List.of(carry), EMPTY_SOURCE);

        // The id-based identity restores the original constraint verbatim; the renamed copy is a distinct item, so
        // stable ids stay unique even though two entries now share the same text. This is the documented boundary.
        assertThat(reconciled.constraints()).singleElement().satisfies(item -> {
            assertThat(item.stableItemId()).isEqualTo("C-1");
            assertThat(item.text()).isEqualTo("Original strict rule: read-only mode required");
        });
        assertThat(reconciled.goals())
                .extracting(SemanticSummaryItem::stableItemId)
                .containsExactly("G-9");
    }

    @Test
    @DisplayName("Reconciled candidate passes SemanticSummaryValidator without throwing validation exceptions")
    void reconciledCandidatePassesSemanticSummaryValidator() {
        SemanticSummaryItem carryConstraint = observed("C-1", "Original mandatory constraint text", "m001");
        SemanticSummaryItem carryQuestion = observed("Q-1", "Original unresolved question", "m001");
        // Candidate has altered text for C-1, dropped Q-1, and placed C-1 inside goals.
        SemanticConversationSummaryV1 candidate = withGoals(observed("C-1", "Completely rewritten text", "m001"));
        ProjectedCompactionSource source = sourceWithAliases(Map.of("m001", new AgentMessageId("msg-1")));

        Assertions.assertThrows(
                SemanticSummaryValidationException.class,
                () -> SemanticSummaryValidator.validate(
                        candidate, source, List.of(carryConstraint, carryQuestion), Set.of("msg-1"), Optional.empty()));

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(candidate, List.of(carryConstraint, carryQuestion), source);

        Assertions.assertDoesNotThrow(() -> SemanticSummaryValidator.validate(
                reconciled, source, List.of(carryConstraint, carryQuestion), Set.of("msg-1"), Optional.empty()));
    }
}
