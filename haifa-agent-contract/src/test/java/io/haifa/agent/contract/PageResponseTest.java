package io.haifa.agent.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.contract.common.PageResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageResponseTest {

    @Test
    void copiesItemsAndCalculatesTotalPages() {
        List<String> source = new ArrayList<>(List.of("one", "two"));

        PageResponse<String> response = new PageResponse<>(source, 0, 2, 5);
        source.clear();

        assertThat(response.items()).containsExactly("one", "two");
        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidPagingValues() {
        assertThatThrownBy(() -> new PageResponse<>(List.of("one"), -1, 10, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page");
    }
}
