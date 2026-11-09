package io.haifa.agent.model.api;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalLong;

/** Provider-reported token use plus optional priced cost. */
public record ModelUsage(
        long inputTokens,
        long outputTokens,
        long cacheHitTokens,
        long cacheMissTokens,
        long reasoningTokens,
        boolean costKnown,
        long costMinorUnits,
        OptionalLong cacheWriteTokens,
        boolean inputTokensKnown,
        boolean cacheHitTokensKnown,
        boolean cacheMissTokensKnown) {
    public ModelUsage(
            long inputTokens,
            long outputTokens,
            long cacheHitTokens,
            long cacheMissTokens,
            long reasoningTokens,
            boolean costKnown,
            long costMinorUnits) {
        this(
                inputTokens,
                outputTokens,
                cacheHitTokens,
                cacheMissTokens,
                reasoningTokens,
                costKnown,
                costMinorUnits,
                OptionalLong.empty(),
                true,
                true,
                true);
    }

    public ModelUsage {
        java.util.Objects.requireNonNull(cacheWriteTokens, "cacheWriteTokens");
        if (cacheWriteTokens.isPresent() && cacheWriteTokens.getAsLong() < 0) {
            throw new IllegalArgumentException("cache writes must not be negative");
        }
        if (inputTokens < 0
                || outputTokens < 0
                || cacheHitTokens < 0
                || cacheMissTokens < 0
                || reasoningTokens < 0
                || costMinorUnits < 0) {
            throw new IllegalArgumentException("model usage must not be negative");
        }
        if (inputTokensKnown
                && cacheHitTokensKnown
                && cacheMissTokensKnown
                && cacheWriteTokens.isPresent()
                && Math.addExact(Math.addExact(cacheHitTokens, cacheMissTokens), cacheWriteTokens.getAsLong())
                        != inputTokens) {
            throw new IllegalArgumentException("reported input lanes do not reconcile");
        }
        if (!costKnown && costMinorUnits != 0) {
            throw new IllegalArgumentException("unknown cost must not have a value");
        }
    }

    public static ModelUsage unpriced(long inputTokens, long outputTokens) {
        return new ModelUsage(inputTokens, outputTokens, 0, 0, 0, false, 0, OptionalLong.empty(), true, false, false);
    }

    /** No reported total; numeric zero is a compatibility floor, not evidence of zero use. */
    public static ModelUsage unknown() {
        return new ModelUsage(0, 0, 0, 0, 0, false, 0, OptionalLong.empty(), false, false, false);
    }

    /** Inclusive total: cache reads and writes are subsets, never added to total again. */
    public static ModelUsage inclusiveInput(
            OptionalLong input,
            long output,
            OptionalLong read,
            OptionalLong write,
            OptionalLong uncached,
            long reasoning,
            boolean costKnown,
            long cost) {
        if (uncached.isEmpty() && input.isPresent() && read.isPresent() && write.isPresent()) {
            long value = Math.subtractExact(Math.subtractExact(input.getAsLong(), read.getAsLong()), write.getAsLong());
            if (value < 0) throw new IllegalArgumentException("input components exceed reported total");
            uncached = OptionalLong.of(value);
        }
        if (input.isPresent() && read.isPresent() && read.getAsLong() > input.getAsLong()) {
            throw new IllegalArgumentException("cache reads exceed reported total");
        }
        return new ModelUsage(
                input.orElse(0),
                output,
                read.orElse(0),
                uncached.orElse(0),
                reasoning,
                costKnown,
                cost,
                write,
                input.isPresent(),
                read.isPresent(),
                uncached.isPresent());
    }

    /** Exclusive uncached input: all three disjoint reported lanes are needed for a known total. */
    public static ModelUsage exclusiveInput(OptionalLong uncached, long output, OptionalLong read, OptionalLong write) {
        long partialTotal = Math.addExact(Math.addExact(uncached.orElse(0), read.orElse(0)), write.orElse(0));
        return new ModelUsage(
                partialTotal,
                output,
                read.orElse(0),
                uncached.orElse(0),
                0,
                false,
                0,
                write,
                uncached.isPresent() && read.isPresent() && write.isPresent(),
                read.isPresent(),
                uncached.isPresent());
    }

    /** Ratios must come from the exact binding's current price table; absence is not a zero-priced lane. */
    public Optional<BigDecimal> costWeightedInput(
            BigDecimal uncachedRatio, BigDecimal readRatio, BigDecimal writeRatio) {
        java.util.Objects.requireNonNull(uncachedRatio);
        java.util.Objects.requireNonNull(readRatio);
        java.util.Objects.requireNonNull(writeRatio);
        if (uncachedRatio.signum() < 0 || readRatio.signum() < 0 || writeRatio.signum() < 0)
            throw new IllegalArgumentException("price ratios must not be negative");
        if (!inputTokensKnown || !cacheHitTokensKnown || !cacheMissTokensKnown || cacheWriteTokens.isEmpty())
            return Optional.empty();
        return Optional.of(BigDecimal.valueOf(cacheMissTokens)
                .multiply(uncachedRatio)
                .add(BigDecimal.valueOf(cacheHitTokens).multiply(readRatio))
                .add(BigDecimal.valueOf(cacheWriteTokens.getAsLong()).multiply(writeRatio)));
    }
}
