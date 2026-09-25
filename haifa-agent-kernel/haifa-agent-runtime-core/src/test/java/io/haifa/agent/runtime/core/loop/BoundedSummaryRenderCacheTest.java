package io.haifa.agent.runtime.core.loop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BoundedSummaryRenderCacheTest {
    @Test
    void evictsLeastRecentlyUsedEntriesAtTheEntryLimit() {
        BoundedSummaryRenderCache<String> cache = new BoundedSummaryRenderCache<>(2, 100);
        cache.put("first", "111");
        cache.put("second", "22");

        assertThat(cache.get("first")).contains("111");
        cache.put("third", "3");

        assertThat(cache.get("first")).contains("111");
        assertThat(cache.get("second")).isEmpty();
        assertThat(cache.get("third")).contains("3");
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.characters()).isEqualTo(4);
    }

    @Test
    void enforcesTheCharacterLimitAndDoesNotCacheOversizedValues() {
        BoundedSummaryRenderCache<String> cache = new BoundedSummaryRenderCache<>(10, 5);
        cache.put("first", "123");
        cache.put("second", "456");

        assertThat(cache.get("first")).isEmpty();
        assertThat(cache.get("second")).contains("456");
        assertThat(cache.characters()).isEqualTo(3);

        cache.put("oversized", "123456");

        assertThat(cache.get("oversized")).isEmpty();
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.characters()).isEqualTo(3);
    }
}
