package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.credential.core.DefaultSecretRedactor;
import io.haifa.agent.runtime.api.ApprovalPresentation;
import io.haifa.agent.runtime.api.ApprovalPrompt;
import io.haifa.coding.host.internal.LocalCodingAgent;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalCodingAgentApprovalPresentationTest {

    private static final String COORDINATE = "file_create@2.1.0#haifa-project#digest-1";

    @Test
    void explainsCreateAndWriteWithoutClaimingTheWriteHappened() {
        ApprovalPresentation create = presentation(LocalCodingAgent.fileMutationApprovalPrompt(
                "file_create",
                COORDINATE,
                Map.of(
                        "path", "C:\\work\\new.txt",
                        "content", "hello\nworld",
                        "description", "Add a greeting"),
                new DefaultSecretRedactor(),
                false));

        assertThat(create.title()).isEqualTo("Create file");
        assertThat(create.purpose()).isEqualTo("Add a greeting");
        assertThat(create.contentType()).isEqualTo("File content");
        assertThat(create.content())
                .contains("Target: C:\\work\\new.txt", "Requested content (2 lines, 11 characters)", "hello", "world");
        assertThat(create.environment())
                .anySatisfy(
                        fact -> assertThat(fact.label() + "=" + fact.value()).isEqualTo("Operation=Create file"))
                .anySatisfy(
                        fact -> assertThat(fact.label() + "=" + fact.value()).isEqualTo("Target=C:\\work\\new.txt"));
        assertThat(create.technical()).anySatisfy(fact -> assertThat(fact.label() + "=" + fact.value())
                .isEqualTo("Coordinate=" + COORDINATE));
        assertThat(create.content()).doesNotContain("created", "succeeded");

        ApprovalPrompt fallback = LocalCodingAgent.fileMutationApprovalPrompt(
                        "file_write",
                        "file_write@2.1.0#haifa-project#digest-2",
                        Map.of("path", "C:\\work\\existing.txt", "content", "replacement\n"),
                        new DefaultSecretRedactor(),
                        false)
                .orElseThrow();
        assertThat(fallback.prompt())
                .contains("Operation: Write file", "Target: C:\\work\\existing.txt", "replacement")
                .doesNotContain("created", "succeeded");
        assertThat(fallback.presentation().orElseThrow().content())
                .contains("Requested content", "Target: C:\\work\\existing.txt");
    }

    @Test
    void listsPatchTargetsAndKeepsTheBoundedDiffVisible() {
        String patch = "*** Begin Patch\n"
                + "*** Update File: C:\\work\\a.txt\n"
                + "@@\n"
                + "-old\n"
                + "+new\n"
                + "*** Add File: C:\\work\\b.txt\n"
                + "+created\n"
                + "*** End Patch";
        ApprovalPresentation presentation = presentation(LocalCodingAgent.fileMutationApprovalPrompt(
                "file_patch", "file_patch@2.2.0#haifa-project#digest-3", Map.of("patch", patch), null, false));

        assertThat(presentation.title()).isEqualTo("Apply patch");
        assertThat(presentation.contentType()).isEqualTo("Patch");
        assertThat(presentation.content())
                .contains(
                        "Target: Update C:\\work\\a.txt; Create C:\\work\\b.txt",
                        "Requested patch",
                        "*** Update File: C:\\work\\a.txt",
                        "-old",
                        "+new",
                        "*** Add File: C:\\work\\b.txt");
    }

    @Test
    void reportsTheTruthfulPatchTargetTotalWithoutStoringAnUnboundedPreview() {
        StringBuilder patch = new StringBuilder("*** Begin Patch\n");
        for (int index = 1; index <= 20; index++) {
            patch.append("*** Update File: C:\\work\\file-").append(index).append(".txt\n@@\n-old\n+new\n");
        }
        patch.append("*** End Patch");
        ApprovalPresentation presentation = presentation(LocalCodingAgent.fileMutationApprovalPrompt(
                "file_patch",
                "file_patch@2.2.0#haifa-project#digest-large",
                Map.of("patch", patch.toString()),
                null,
                false));

        assertThat(presentation.content())
                .contains(
                        "Target: 20 declared targets; first 3: Update C:\\work\\file-1.txt; "
                                + "Update C:\\work\\file-2.txt; Update C:\\work\\file-3.txt; … (17 not shown)",
                        "*** Update File: C:\\work\\file-20.txt");
    }

    @Test
    void explainsDeleteAndMoveWithoutReadingFiles() {
        ApprovalPresentation delete = presentation(LocalCodingAgent.fileMutationApprovalPrompt(
                "file_delete",
                "file_delete@2.1.0#haifa-project#digest-4",
                Map.of("path", "C:\\work\\obsolete.txt"),
                null,
                false));
        assertThat(delete.title()).isEqualTo("Delete file");
        assertThat(delete.content())
                .contains("Target: C:\\work\\obsolete.txt", "No file content is read", "reports success or failure");

        ApprovalPresentation move = presentation(LocalCodingAgent.fileMutationApprovalPrompt(
                "file_move",
                "file_move@2.1.0#haifa-project#digest-5",
                Map.of("source", "C:\\work\\before.txt", "destination", "C:\\work\\after.txt"),
                null,
                false));
        assertThat(move.title()).isEqualTo("Move file");
        assertThat(move.content())
                .contains(
                        "Target: C:\\work\\before.txt → C:\\work\\after.txt",
                        "No file content is read",
                        "reports success or failure");
    }

    @Test
    void preservesTheReauthenticationDistinctionInTheTitleAndPurpose() {
        ApprovalPresentation presentation = presentation(LocalCodingAgent.fileMutationApprovalPrompt(
                "file_write",
                "file_write@2.1.0#haifa-project#digest-reauth",
                Map.of("path", "C:\\work\\secure.txt", "content", "value\n"),
                null,
                true));

        assertThat(presentation.title()).isEqualTo("Reauthenticate and write file");
        assertThat(presentation.purpose()).startsWith("Reauthentication required. ");
        assertThat(presentation.technical()).anySatisfy(fact -> assertThat(fact.label() + "=" + fact.value())
                .isEqualTo("Reauthentication=required"));
    }

    @Test
    void keepsUnknownAndNonMutationToolsOnTheNeutralFallbackPath() {
        assertThat(LocalCodingAgent.fileMutationApprovalPrompt(
                        "file_read", COORDINATE, Map.of("path", "C:\\work\\a.txt"), null, false))
                .isEmpty();
        assertThat(LocalCodingAgent.fileMutationApprovalPrompt(
                        "execution_run", COORDINATE, Map.of("command", "dir"), null, false))
                .isEmpty();
        assertThat(LocalCodingAgent.fileMutationApprovalPrompt(
                        "third_party_mutation", COORDINATE, Map.of("value", "x"), null, false))
                .isEmpty();
    }

    @Test
    void redactsCredentialsFromTheRequestedContentAndPromptPreview() {
        ApprovalPrompt prompt = LocalCodingAgent.fileMutationApprovalPrompt(
                        "file_create",
                        COORDINATE,
                        Map.of(
                                "path", "C:\\work\\config.txt",
                                "content", "api_key=topsecretvalue\nsafe line"),
                        new DefaultSecretRedactor(),
                        false)
                .orElseThrow();

        assertThat(prompt.prompt()).doesNotContain("topsecretvalue");
        assertThat(prompt.presentation().orElseThrow().content())
                .contains("[REDACTED]", "safe line")
                .doesNotContain("topsecretvalue");

        ApprovalPresentation patch = presentation(LocalCodingAgent.fileMutationApprovalPrompt(
                "file_patch",
                "file_patch@2.2.0#haifa-project#digest-redacted-path",
                Map.of(
                        "patch",
                        "*** Begin Patch\n*** Update File: C:\\work\\api_key=topsecretvalue.txt\n@@\n-old\n+new\n*** End Patch"),
                new DefaultSecretRedactor(),
                false));
        assertThat(patch.content()).contains("[REDACTED]").doesNotContain("topsecretvalue");
    }

    private static ApprovalPresentation presentation(java.util.Optional<ApprovalPrompt> prompt) {
        return prompt.orElseThrow().presentation().orElseThrow();
    }
}
