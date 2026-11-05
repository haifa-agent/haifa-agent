package io.haifa.agent.application.project.product.coding.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.*;
import io.haifa.agent.credential.api.SecretRedactor;
import io.haifa.coding.api.CodingProtocol.*;
import java.util.ArrayList;
import java.util.List;

/** Product-owned mapping shared by the live window and independent, UTF-8 bounded history pages. */
public final class CodingPresentationHistory {
    private CodingPresentationHistory() {}

    static Entry entry(CodingSessionHistoryItem item, SecretRedactor redactor) {
        return new Entry(
                item.id(),
                item.kind() == CodingSessionHistoryItem.Kind.ERROR ? "notice" : "message",
                item.sourceRunId().orElse(null),
                item.kind().name().toLowerCase(java.util.Locale.ROOT),
                CodingPresentationText.safe(item.title(), 256, redactor),
                CodingPresentationText.safe(item.body(), 8000, redactor),
                item.status(),
                item.sequence(),
                item.truncated(),
                null,
                item.createdAt().toEpochMilli(),
                null,
                null,
                item.code().orElse(null),
                item.diagnosticId().orElse(null));
    }

    public static HistoryPage page(CodingSessionHistoryPage source, SecretRedactor redactor) {
        List<Entry> entries = new ArrayList<>(
                source.items().stream().map(item -> entry(item, redactor)).toList());
        boolean earlier = source.earlierHistoryAvailable();
        Long before = source.nextBeforeSequence().isPresent()
                ? Long.valueOf(source.nextBeforeSequence().getAsLong())
                : null;
        boolean complete = source.items().stream().noneMatch(CodingSessionHistoryItem::truncated);
        var json = new ObjectMapper();
        while (true) {
            while (entries.size() > 100) {
                Long first = entries.getFirst().sequence();
                entries.removeIf(item -> first.equals(item.sequence()));
                earlier = true;
                before = entries.isEmpty() ? first + 1 : entries.getFirst().sequence();
                complete = false;
            }
            var page = new HistoryPage(source.sessionId().value(), entries, new History(earlier, before, complete));
            try {
                if (json.writeValueAsBytes(page).length <= 256 * 1024) return page;
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("HISTORY_ENCODING_FAILED");
            }
            // Keep the newest complete source-message group and paginate strictly before the oldest retained one.
            Long sequence = entries.getFirst().sequence();
            entries.removeIf(item -> sequence.equals(item.sequence()));
            earlier = true;
            before = entries.isEmpty() ? sequence + 1 : entries.getFirst().sequence();
            complete = false;
        }
    }
}
