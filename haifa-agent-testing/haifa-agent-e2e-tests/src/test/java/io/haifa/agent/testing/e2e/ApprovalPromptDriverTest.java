package io.haifa.agent.testing.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApprovalPromptDriverTest {
    @Test
    void respondsOnlyAfterEachExpectedTargetIsVisible() {
        var driver = new ApprovalPromptDriver(List.of(
                new ApprovalPromptDriver.Decision("web_search", "n"),
                new ApprovalPromptDriver.Decision("web_fetch", "y")));

        assertThat(feed(driver, "Approve tool web_search (web.search@1)" + ApprovalPromptDriver.PROMPT_SUFFIX))
                .containsExactly("n" + System.lineSeparator());
        assertThat(feed(driver, "Approve tool web_fetch (web.fetch@1)" + ApprovalPromptDriver.PROMPT_SUFFIX))
                .containsExactly("y" + System.lineSeparator());
        driver.assertComplete();
    }

    @Test
    void failsClosedForWrongMissingOrAdditionalPrompts() {
        var wrong = new ApprovalPromptDriver(List.of(new ApprovalPromptDriver.Decision("web_search", "y")));
        assertThatThrownBy(
                        () -> feed(wrong, "Approve tool web_fetch (web.fetch@1)" + ApprovalPromptDriver.PROMPT_SUFFIX))
                .hasMessageContaining("expected target web_search");

        var missing = new ApprovalPromptDriver(List.of(new ApprovalPromptDriver.Decision("web_search", "y")));
        assertThatThrownBy(missing::assertComplete).hasMessageContaining("expected 1 approval prompts");

        var additional = new ApprovalPromptDriver(List.of());
        assertThatThrownBy(() -> feed(additional, "Approve tool web_search" + ApprovalPromptDriver.PROMPT_SUFFIX))
                .hasMessageContaining("unexpected additional approval prompt");
    }

    private static List<String> feed(ApprovalPromptDriver driver, String text) {
        return text.chars()
                .mapToObj(value -> driver.accept((char) value))
                .flatMap(Optional::stream)
                .toList();
    }
}
