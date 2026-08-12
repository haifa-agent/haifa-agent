package io.haifa.agent.personalassistant.application.mission;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves supported relative Research Brief ranges once, before the Mission is persisted and planned. */
final class ResearchTimeRangeFreezer {
    private static final Pattern RELATIVE_RANGE = Pattern.compile("^(?:过去|近)\\s*(\\d+)\\s*(年|个月|月)(?:至今)?$");
    private static final Pattern ONE_YEAR = Pattern.compile("^(?:过去|近)\\s*一年(?:至今)?$");
    private static final Pattern ONE_MONTH = Pattern.compile("^(?:过去|近)\\s*一个月(?:至今)?$");

    private ResearchTimeRangeFreezer() {}

    static Optional<ResearchBrief> freeze(Optional<ResearchBrief> brief, Instant createdAt) {
        return brief.map(value -> freeze(value, createdAt));
    }

    private static ResearchBrief freeze(ResearchBrief brief, Instant createdAt) {
        String frozenRange = freezeRange(brief.timeRange(), createdAt);
        if (frozenRange.equals(brief.timeRange())) return brief;
        return new ResearchBrief(
                brief.question(),
                brief.scope(),
                frozenRange,
                brief.region(),
                brief.audience(),
                brief.sourcePreferences(),
                brief.exclusions(),
                brief.deliveryFormat());
    }

    static String freezeRange(String range, Instant createdAt) {
        String value = range == null ? "" : range.trim();
        if (value.isEmpty()) return value;
        LocalDate end = createdAt.atZone(ZoneOffset.UTC).toLocalDate();
        if (ONE_YEAR.matcher(value).matches()) return explicit(end.minusYears(1), end);
        if (ONE_MONTH.matcher(value).matches()) return explicit(end.minusMonths(1), end);
        Matcher match = RELATIVE_RANGE.matcher(value);
        if (!match.matches()) return value;
        int amount;
        try {
            amount = Integer.parseInt(match.group(1));
        } catch (NumberFormatException ignored) {
            return value;
        }
        if (amount < 1 || amount > 100) return value;
        LocalDate start = "年".equals(match.group(2)) ? end.minusYears(amount) : end.minusMonths(amount);
        return explicit(start, end);
    }

    private static String explicit(LocalDate start, LocalDate end) {
        return start + " 至 " + end + "（UTC，创建时冻结）";
    }
}
