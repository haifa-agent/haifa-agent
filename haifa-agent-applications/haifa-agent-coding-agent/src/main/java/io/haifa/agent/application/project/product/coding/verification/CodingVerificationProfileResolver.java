package io.haifa.agent.application.project.product.coding.verification;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies source priority independently at each verification-ladder trigger. */
public final class CodingVerificationProfileResolver {
    private static final Comparator<CodingVerificationCandidate> ORDER = Comparator.comparing(
                    CodingVerificationCandidate::trigger)
            .thenComparingInt(candidate -> candidate.source().priority())
            .thenComparing(CodingVerificationCandidate::cost)
            .thenComparing(CodingVerificationCandidate::command);

    public CodingVerificationProfile resolve(
            List<CodingVerificationCandidate> userExplicit,
            List<CodingVerificationCandidate> repository,
            List<CodingVerificationCandidate> adjacent,
            List<CodingVerificationCandidate> ecosystemDefaults) {
        List<CodingVerificationCandidate> all = new ArrayList<>();
        all.addAll(required(userExplicit, "userExplicit"));
        all.addAll(required(repository, "repository"));
        all.addAll(required(adjacent, "adjacent"));
        all.addAll(required(ecosystemDefaults, "ecosystemDefaults"));
        return resolve(all);
    }

    public CodingVerificationProfile resolve(List<CodingVerificationCandidate> candidates) {
        List<CodingVerificationCandidate> all = new ArrayList<>(required(candidates, "candidates"));
        all = new ArrayList<>(new LinkedHashSet<>(all));
        if (all.size() > CodingVerificationProfile.MAXIMUM_CANDIDATES * 2) {
            throw new IllegalArgumentException("verification candidate input exceeds its bound");
        }

        Map<CodingVerificationTrigger, Integer> best = new EnumMap<>(CodingVerificationTrigger.class);
        all.forEach(
                candidate -> best.merge(candidate.trigger(), candidate.source().priority(), Math::min));
        List<CodingVerificationCandidate> selected = all.stream()
                .filter(candidate -> candidate.source().priority() == best.get(candidate.trigger()))
                .sorted(ORDER)
                .toList();
        List<CodingVerificationCandidate> ignored = all.stream()
                .filter(candidate -> candidate.source().priority() != best.get(candidate.trigger()))
                .sorted(ORDER)
                .toList();
        return new CodingVerificationProfile(selected, ignored);
    }

    private static List<CodingVerificationCandidate> required(List<CodingVerificationCandidate> value, String field) {
        return List.copyOf(Objects.requireNonNull(value, field + " must not be null"));
    }
}
