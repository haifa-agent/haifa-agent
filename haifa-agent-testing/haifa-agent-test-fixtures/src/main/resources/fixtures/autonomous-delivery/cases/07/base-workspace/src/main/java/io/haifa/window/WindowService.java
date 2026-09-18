package io.haifa.window;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

public final class WindowService {
    private WindowService() {}

    public static List<Event> eventsForLocalDate(
            List<Event> events, LocalDate localDate, ZoneId zoneId) {
        var start = localDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        var end = localDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return events.stream()
                .filter(
                        event ->
                                !event.occurredAt().isBefore(start)
                                        && event.occurredAt().isBefore(end))
                .toList();
    }
}
