package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimeEvent;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeEventJournalContractTest {
    private static final Instant NOW = SqliteAggregateTestData.NOW;

    @Test
    void inMemoryAdapterSatisfiesJournalRangeContract() {
        verifyJournalContract(new InMemoryRuntimeStore(), new AgentRunId("run"));
    }

    @Test
    void sqliteAdapterSatisfiesJournalRangeContractAndUsesIndexedQuery(@TempDir java.nio.file.Path directory)
            throws Exception {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        var run = SqliteAggregateTestData.prepareRun(foundation);

        verifyJournalContract(foundation.events(), run.id());

        try (var connection = foundation.connections().openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        """
                        EXPLAIN QUERY PLAN
                        SELECT event_id, run_id, sequence, type, event_schema_version,
                               data_schema_version, data_payload, data_hash,
                               correlation_id, causation_id, occurred_at
                        FROM runtime_event
                        WHERE run_id = ? AND sequence > ? AND sequence <= ?
                        ORDER BY sequence
                        LIMIT ?
                        """)) {
            statement.setString(1, run.id().value());
            statement.setLong(2, 0);
            statement.setLong(3, Long.MAX_VALUE);
            statement.setInt(4, 10);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("detail"))
                        .contains("SEARCH runtime_event")
                        .contains("run_id=?")
                        .contains("sequence>?");
            }
        }
    }

    private static void verifyJournalContract(RuntimeEventAppender journal, AgentRunId runId) {
        RuntimeEvent first = journal.append(runId, "one", Map.of("value", 1), NOW);
        RuntimeEvent second = journal.append(runId, "two", Map.of("value", 2), NOW.plusMillis(1));
        RuntimeEvent third = journal.append(runId, "three", Map.of("value", 3), NOW.plusMillis(2));

        assertThat(first.sequence()).isEqualTo(1);
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(third.sequence()).isEqualTo(3);
        assertThat(first.eventId()).isNotEqualTo(second.eventId());
        assertThat(journal.earliestSequence(runId)).hasValue(1);
        assertThat(journal.headSequence(runId)).hasValue(3);
        assertThat(journal.eventsAfter(runId, 1, OptionalLong.of(3), 1).events())
                .containsExactly(second);

        RuntimeEvent fourth = journal.append(runId, "four", Map.of("value", 4), NOW.plusMillis(3));
        var fixedHead = journal.eventsAfter(runId, 2, OptionalLong.of(3), 10);
        assertThat(fixedHead.events()).containsExactly(third);
        assertThat(fixedHead.headSequence()).hasValue(3);
        assertThat(journal.eventsAfter(runId, 3, OptionalLong.empty(), 10).events())
                .containsExactly(fourth);
        assertThat(journal.eventsFor(runId))
                .extracting(RuntimeEvent::eventId)
                .containsExactly(first.eventId(), second.eventId(), third.eventId(), fourth.eventId());

        assertThat(journal.deleteBefore(runId, 3, NOW.plusSeconds(1))).isEqualTo(2);
        assertThat(journal.earliestSequence(runId)).hasValue(3);
        assertThat(journal.headSequence(runId)).hasValue(4);
        assertThat(journal.eventsAfter(runId, 2, OptionalLong.empty(), 10).events())
                .containsExactly(third, fourth);
    }
}
