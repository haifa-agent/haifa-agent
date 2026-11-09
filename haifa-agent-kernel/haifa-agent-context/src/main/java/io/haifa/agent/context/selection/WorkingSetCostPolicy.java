package io.haifa.agent.context.selection;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** Deterministic prediction over three input lanes; it makes no claim about actual Provider cache hits. */
public final class WorkingSetCostPolicy {
    public static final String VERSION = "working-set-cost-v1";

    private WorkingSetCostPolicy() {}

    public record Lanes(long read, long write, long uncached) {
        public Lanes {
            if (read < 0 || write < 0 || uncached < 0) throw new IllegalArgumentException("negative input lane");
        }
    }

    public record Prices(BigDecimal read, BigDecimal write, BigDecimal uncached) {
        public Prices {
            for (var price : java.util.List.of(read, write, uncached))
                if (price.signum() < 0) throw new IllegalArgumentException("negative price");
        }
    }

    public record Decision(
            boolean defer, String reason, Optional<BigDecimal> retainCost, Optional<BigDecimal> reselectCost) {}

    public static Decision decide(
            Lanes retain,
            Lanes reselect,
            Optional<Prices> prices,
            int expectedCalls,
            boolean reusable,
            boolean qualityAllowsReuse,
            long retainedTokens,
            long retentionLimit,
            int deferredCalls,
            int maxDeferredCalls) {
        Objects.requireNonNull(retain);
        Objects.requireNonNull(reselect);
        Objects.requireNonNull(prices);
        if (expectedCalls < 1 || retainedTokens < 0 || retentionLimit < 1 || deferredCalls < 0 || maxDeferredCalls < 1)
            throw new IllegalArgumentException("invalid decision bounds");
        Optional<BigDecimal> keep = prices.map(p -> cost(retain, p, expectedCalls));
        Optional<BigDecimal> change = prices.map(p -> cost(reselect, p, expectedCalls));
        String forced = !qualityAllowsReuse
                ? "QUALITY_OR_GOAL_CHANGED"
                : !reusable
                        ? "PREFIX_SOURCE_OR_TTL_MISMATCH"
                        : retainedTokens >= retentionLimit
                                ? "RETENTION_LIMIT"
                                : deferredCalls >= maxDeferredCalls
                                        ? "DEFERRED_CALL_LIMIT"
                                        : prices.isEmpty() ? "CACHE_COST_UNAVAILABLE" : "";
        if (!forced.isEmpty()) return new Decision(false, forced, keep, change);
        boolean defer = keep.orElseThrow().compareTo(change.orElseThrow()) < 0;
        return new Decision(defer, defer ? "BOUNDED_CACHE_DEFER" : "RESELECT_CHEAPER", keep, change);
    }

    public static BigDecimal cost(Lanes lanes, Prices prices, int calls) {
        // A newly written prefix is written once, then read on the remaining predicted calls.
        return prices.read()
                .multiply(BigDecimal.valueOf(lanes.read()).multiply(BigDecimal.valueOf(calls)))
                .add(prices.write().multiply(BigDecimal.valueOf(lanes.write())))
                .add(prices.read().multiply(BigDecimal.valueOf(lanes.write()).multiply(BigDecimal.valueOf(calls - 1))))
                .add(prices.uncached()
                        .multiply(BigDecimal.valueOf(lanes.uncached()).multiply(BigDecimal.valueOf(calls))));
    }
}
