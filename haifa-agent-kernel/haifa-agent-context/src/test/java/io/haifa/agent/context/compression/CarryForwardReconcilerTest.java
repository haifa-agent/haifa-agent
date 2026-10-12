package io.haifa.agent.context.compression;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.message.AgentMessageId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CarryForwardReconcilerTest {

    private static final ProjectedCompactionSource EMPTY_SOURCE =
            new ProjectedCompactionSource("", Map.of(), Map.of(), List.of(), List.of(), Set.of());

    private static ProjectedCompactionSource sourceWithAliases(Map<String, AgentMessageId> msgAliases) {
        return new ProjectedCompactionSource("", msgAliases, Map.of(), List.of(), List.of(), Set.of());
    }

    private static SemanticConversationSummaryV1 createBaseSummary() {
        return new SemanticConversationSummaryV1(
                "v1", "en", List.of(), List.of(), SemanticProgress.empty(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("Overwrites candidate text with original carry-forward text verbatim")
    void overwritesModifiedTextWithVerbatimCarryText() {
        SemanticSummaryItem carryConstraint = new SemanticSummaryItem(
                "C-1", "Original strict rule: read-only mode required", List.of("m001"), SemanticConfidence.OBSERVED);
        SemanticSummaryItem candidateConstraint = new SemanticSummaryItem(
                "C-1", "LLM paraphrased text: must be read-only", List.of("m001"), SemanticConfidence.OBSERVED);

        SemanticConversationSummaryV1 candidate = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(),
                List.of(candidateConstraint),
                SemanticProgress.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(candidate, List.of(carryConstraint), EMPTY_SOURCE);

        assertThat(reconciled.constraints()).hasSize(1);
        assertThat(reconciled.constraints().get(0).stableItemId()).isEqualTo("C-1");
        assertThat(reconciled.constraints().get(0).text()).isEqualTo("Original strict rule: read-only mode required");
    }

    @Test
    @DisplayName("Merges supporting sourceRefs from carry and candidate without loss")
    void mergesSourceRefs() {
        SemanticSummaryItem carryItem =
                new SemanticSummaryItem("C-1", "Rule A", List.of("m001", "m002"), SemanticConfidence.OBSERVED);
        SemanticSummaryItem candidateItem = new SemanticSummaryItem(
                "C-1", "Rule A paraphrased", List.of("m002", "m003"), SemanticConfidence.OBSERVED);

        SemanticConversationSummaryV1 candidate = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(),
                List.of(candidateItem),
                SemanticProgress.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(candidate, List.of(carryItem), EMPTY_SOURCE);

        assertThat(reconciled.constraints().get(0).sourceRefs()).containsExactly("m001", "m002", "m003");
    }

    @Test
    @DisplayName("Restores missing mandatory carry-forward item if not resolved by terminal decision")
    void restoresMissingUnresolvedItem() {
        SemanticSummaryItem carryItem = new SemanticSummaryItem(
                "Q-1", "Open question about auth scope", List.of("m001"), SemanticConfidence.OBSERVED);

        SemanticConversationSummaryV1 candidate = createBaseSummary();

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(candidate, List.of(carryItem), EMPTY_SOURCE);

        assertThat(reconciled.unresolvedQuestions()).hasSize(1);
        assertThat(reconciled.unresolvedQuestions().get(0)).isEqualTo(carryItem);
    }

    @Test
    @DisplayName("Allows dropping missing carry item when resolved by accepted decision citing current evidence")
    void allowsDroppingResolvedItem() {
        SemanticSummaryItem carryItem = new SemanticSummaryItem(
                "Q-1", "Open question about auth scope", List.of("m001"), SemanticConfidence.OBSERVED);

        SemanticDecisionItem resolvingDecision = new SemanticDecisionItem(
                "D-1",
                "Resolved Q-1: use read scope",
                "Decided based on user prompt",
                SemanticDecisionStatus.ACCEPTED,
                List.of("m002"));

        SemanticConversationSummaryV1 candidate = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(),
                List.of(),
                SemanticProgress.empty(),
                List.of(resolvingDecision),
                List.of(),
                List.of(),
                List.of());

        ProjectedCompactionSource source = sourceWithAliases(Map.of("m002", new AgentMessageId("msg-2")));

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(candidate, List.of(carryItem), source);

        assertThat(reconciled.unresolvedQuestions()).isEmpty();
    }

    @Test
    @DisplayName("Does not drop missing carry item if decision is PROPOSED")
    void doesNotDropIfDecisionIsProposed() {
        SemanticSummaryItem carryItem = new SemanticSummaryItem(
                "Q-1", "Open question about auth scope", List.of("m001"), SemanticConfidence.OBSERVED);

        SemanticDecisionItem resolvingDecision = new SemanticDecisionItem(
                "D-1",
                "Proposed resolution for Q-1",
                "Need confirmation",
                SemanticDecisionStatus.PROPOSED,
                List.of("m002"));

        SemanticConversationSummaryV1 candidate = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(),
                List.of(),
                SemanticProgress.empty(),
                List.of(resolvingDecision),
                List.of(),
                List.of(),
                List.of());

        ProjectedCompactionSource source = sourceWithAliases(Map.of("m002", new AgentMessageId("msg-2")));

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(candidate, List.of(carryItem), source);

        assertThat(reconciled.unresolvedQuestions()).containsExactly(carryItem);
    }

    @Test
    @DisplayName("Atomically moves misplaced constraint from goals to constraints without duplication")
    void movesMisplacedConstraintBackToConstraints() {
        SemanticSummaryItem carryConstraint =
                new SemanticSummaryItem("C-1", "Strict constraint", List.of("m001"), SemanticConfidence.OBSERVED);

        SemanticSummaryItem misplacedItem = new SemanticSummaryItem(
                "C-1", "Paraphrased strict constraint", List.of("m001"), SemanticConfidence.OBSERVED);

        SemanticConversationSummaryV1 candidate = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(misplacedItem), // placed in goals!
                List.of(),
                SemanticProgress.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(candidate, List.of(carryConstraint), EMPTY_SOURCE);

        assertThat(reconciled.goals()).isEmpty();
        assertThat(reconciled.constraints()).hasSize(1);
        assertThat(reconciled.constraints().get(0).stableItemId()).isEqualTo("C-1");
        assertThat(reconciled.constraints().get(0).text()).isEqualTo("Strict constraint");
    }

    @Test
    @DisplayName("Supports lifecycle transition of active item to completed with OBSERVED confidence")
    void supportsProgressLifecycleTransitionToCompleted() {
        SemanticSummaryItem carryActive = new SemanticSummaryItem(
                "PA-1", "Compile and run unit tests", List.of("m001"), SemanticConfidence.INFERRED);

        SemanticSummaryItem candidateCompleted = new SemanticSummaryItem(
                "PA-1", "Compile and run unit tests (done)", List.of("m002"), SemanticConfidence.INFERRED);

        SemanticConversationSummaryV1 candidate = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(),
                List.of(),
                new SemanticProgress(List.of(candidateCompleted), List.of(), List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(candidate, List.of(carryActive), EMPTY_SOURCE);

        assertThat(reconciled.progress().active()).isEmpty();
        assertThat(reconciled.progress().completed()).hasSize(1);
        SemanticSummaryItem completed = reconciled.progress().completed().get(0);
        assertThat(completed.stableItemId()).isEqualTo("PA-1");
        assertThat(completed.text()).isEqualTo("Compile and run unit tests");
        assertThat(completed.confidence()).isEqualTo(SemanticConfidence.OBSERVED);
        assertThat(completed.sourceRefs()).containsExactly("m001", "m002");
    }

    @Test
    @DisplayName("Deduplicates duplicate items output by model with same carry ID")
    void deduplicatesDuplicateItems() {
        SemanticSummaryItem carry =
                new SemanticSummaryItem("C-1", "Constraint verbatim", List.of("m001"), SemanticConfidence.OBSERVED);

        SemanticSummaryItem dupInGoals =
                new SemanticSummaryItem("C-1", "Dup 1", List.of("m002"), SemanticConfidence.OBSERVED);
        SemanticSummaryItem dupInConstraints =
                new SemanticSummaryItem("C-1", "Dup 2", List.of("m003"), SemanticConfidence.OBSERVED);

        SemanticConversationSummaryV1 candidate = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(dupInGoals),
                List.of(dupInConstraints),
                SemanticProgress.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(candidate, List.of(carry), EMPTY_SOURCE);

        assertThat(reconciled.goals()).isEmpty();
        assertThat(reconciled.constraints()).hasSize(1);
        assertThat(reconciled.constraints().get(0).stableItemId()).isEqualTo("C-1");
        assertThat(reconciled.constraints().get(0).text()).isEqualTo("Constraint verbatim");
        assertThat(reconciled.constraints().get(0).sourceRefs()).containsExactly("m001", "m003", "m002");
    }

    @Test
    @DisplayName("Reconciled candidate passes SemanticSummaryValidator without throwing validation exceptions")
    void reconciledCandidatePassesSemanticSummaryValidator() {
        SemanticSummaryItem carryConstraint = new SemanticSummaryItem(
                "C-1", "Original mandatory constraint text", List.of("m001"), SemanticConfidence.OBSERVED);
        SemanticSummaryItem carryQuestion = new SemanticSummaryItem(
                "Q-1", "Original unresolved question", List.of("m001"), SemanticConfidence.OBSERVED);

        // Candidate has altered text for C-1, dropped Q-1, and placed C-1 inside goals
        SemanticSummaryItem badCandidateConstraint = new SemanticSummaryItem(
                "C-1", "Completely rewritten text", List.of("m001"), SemanticConfidence.OBSERVED);

        SemanticConversationSummaryV1 candidate = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(badCandidateConstraint),
                List.of(),
                SemanticProgress.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        ProjectedCompactionSource source = sourceWithAliases(Map.of("m001", new AgentMessageId("msg-1")));

        // Without reconciliation, validator fails
        org.junit.jupiter.api.Assertions.assertThrows(
                SemanticSummaryValidationException.class,
                () -> SemanticSummaryValidator.validate(
                        candidate,
                        source,
                        List.of(carryConstraint, carryQuestion),
                        Set.of("msg-1"),
                        java.util.Optional.empty()));

        // With reconciliation, it heals and passes validator smoothly
        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(candidate, List.of(carryConstraint, carryQuestion), source);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> SemanticSummaryValidator.validate(
                reconciled,
                source,
                List.of(carryConstraint, carryQuestion),
                Set.of("msg-1"),
                java.util.Optional.empty()));
    }
}
