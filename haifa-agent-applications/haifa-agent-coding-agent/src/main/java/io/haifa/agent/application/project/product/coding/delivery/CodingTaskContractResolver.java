package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Conservative product resolver. Trusted metadata wins; otherwise multiple structural request
 * signals are required and ambiguous requests remain UNKNOWN.
 */
public final class CodingTaskContractResolver {
    private static final Set<String> ANALYSIS_OPENERS =
            Set.of("analyze", "analyse", "explain", "investigate", "分析", "解释", "调查");
    private static final Set<String> REVIEW_OPENERS = Set.of("review", "audit", "critique", "审查", "评审", "审核");
    private static final Set<String> CREATE_OPENERS =
            Set.of("create", "add", "implement", "build", "write", "创建", "新增", "实现");
    private static final Set<String> CHANGE_OPENERS =
            Set.of("change", "fix", "update", "modify", "refactor", "修复", "修改", "更新", "重构");
    private static final int MAX_ACCEPTANCE_CRITERIA_REFS = 16;
    private static final int MAX_ACCEPTANCE_CRITERIA_REF_LENGTH = 256;
    private static final Pattern ACCEPTANCE_CRITERIA_REF = Pattern.compile("(?<![\\\\/:\\p{L}\\p{N}_./-])"
            + "((?:[\\p{L}\\p{N}_.-]+/)*[\\p{L}\\p{N}_.-]+\\.(?i:md|markdown|txt|rst|adoc))"
            + "(?![\\\\/\\p{L}\\p{N}_./-])");

    private final RuntimeStateRepository state;

    public CodingTaskContractResolver(RuntimeStateRepository state) {
        this.state = Objects.requireNonNull(state, "state must not be null");
    }

    public CodingTaskContract resolve(AgentRun run) {
        AgentMessage initial = state.messages(run.id()).stream()
                .filter(message -> message.role() == MessageRole.USER)
                .min(java.util.Comparator.comparingLong(AgentMessage::sequence))
                .orElse(null);
        Optional<CodingTaskIntent> trusted = initial == null ? Optional.empty() : trustedIntent(initial.metadata());
        Resolution resolution = trusted.map(value -> new Resolution(value, CodingTaskIntentSource.TRUSTED_CALLER, 100))
                .orElseGet(() -> resolveText(text(initial)));
        return CodingTaskContract.freeze(
                run.id().value(),
                resolution.intent(),
                resolution.source(),
                resolution.confidencePercent(),
                acceptanceCriteriaRefs(text(initial)),
                requirements(resolution.intent()),
                run.createdAt());
    }

    private static Resolution resolveText(String request) {
        String normalized = request.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return new Resolution(CodingTaskIntent.UNKNOWN, CodingTaskIntentSource.LOW_CONFIDENCE_FALLBACK, 0);
        }
        String opener = normalized.split("[\\s，。,:：;；!?！？]+", 2)[0];
        boolean questionShape = normalized.endsWith("?")
                || normalized.endsWith("？")
                || normalized.startsWith("why ")
                || normalized.startsWith("how ");
        if (matchesOpener(normalized, opener, REVIEW_OPENERS)) {
            return new Resolution(CodingTaskIntent.REVIEW, CodingTaskIntentSource.PRODUCT_RESOLVER, 90);
        }
        if (matchesOpener(normalized, opener, ANALYSIS_OPENERS) && (questionShape || hasSuffix(normalized, opener))) {
            return new Resolution(CodingTaskIntent.ANALYZE, CodingTaskIntentSource.PRODUCT_RESOLVER, 85);
        }
        if (matchesOpener(normalized, opener, CREATE_OPENERS) && hasSuffix(normalized, opener)) {
            return new Resolution(CodingTaskIntent.CREATE, CodingTaskIntentSource.PRODUCT_RESOLVER, 80);
        }
        if (matchesOpener(normalized, opener, CHANGE_OPENERS) && hasSuffix(normalized, opener)) {
            return new Resolution(CodingTaskIntent.CHANGE, CodingTaskIntentSource.PRODUCT_RESOLVER, 80);
        }
        return new Resolution(CodingTaskIntent.UNKNOWN, CodingTaskIntentSource.LOW_CONFIDENCE_FALLBACK, 25);
    }

    private static boolean matchesOpener(String normalized, String tokenizedOpener, Set<String> candidates) {
        return candidates.contains(tokenizedOpener)
                || candidates.stream()
                        .anyMatch(candidate -> normalized.startsWith(candidate)
                                && candidate.codePoints().anyMatch(codePoint -> codePoint > 127)
                                && normalized.length() > candidate.length());
    }

    private static boolean hasSuffix(String normalized, String tokenizedOpener) {
        return normalized.length() > tokenizedOpener.length()
                || java.util.stream.Stream.of(ANALYSIS_OPENERS, CREATE_OPENERS, CHANGE_OPENERS)
                        .flatMap(Set::stream)
                        .anyMatch(candidate -> normalized.startsWith(candidate)
                                && candidate.codePoints().anyMatch(codePoint -> codePoint > 127)
                                && normalized.length() > candidate.length());
    }

    private static Optional<CodingTaskIntent> trustedIntent(Map<String, Object> metadata) {
        if (!Boolean.TRUE.equals(metadata.get("codingTaskIntentTrusted"))) return Optional.empty();
        Object raw = metadata.get("codingTaskIntent");
        if (!(raw instanceof String value)) return Optional.empty();
        try {
            return Optional.of(CodingTaskIntent.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("trusted coding task intent is invalid", invalid);
        }
    }

    private static Set<String> acceptanceCriteriaRefs(String request) {
        var refs = new TreeSet<String>();
        var matcher = ACCEPTANCE_CRITERIA_REF.matcher(request);
        while (matcher.find() && refs.size() < MAX_ACCEPTANCE_CRITERIA_REFS) {
            String candidate = matcher.group(1);
            if (candidate.length() > MAX_ACCEPTANCE_CRITERIA_REF_LENGTH) continue;
            boolean unsafe = candidate.startsWith(".")
                    || candidate.endsWith(".")
                    || java.util.Arrays.stream(candidate.split("/")).anyMatch(segment -> segment.equals(".."));
            if (!unsafe) refs.add(candidate);
        }
        return Set.copyOf(refs);
    }

    private static String text(AgentMessage message) {
        if (message == null) return "";
        return message.contents().stream()
                .filter(TextPart.class::isInstance)
                .map(TextPart.class::cast)
                .map(TextPart::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private static Set<CodingDeliveryRequirement> requirements(CodingTaskIntent intent) {
        return switch (intent) {
            case CHANGE, CREATE ->
                Set.of(
                        CodingDeliveryRequirement.WORKSPACE_CHANGE_OR_JUSTIFICATION,
                        CodingDeliveryRequirement.VALIDATION_ATTEMPT,
                        CodingDeliveryRequirement.DIFF_INSPECTION);
            case ANALYZE, REVIEW -> Set.of(CodingDeliveryRequirement.READ_ONLY_EVIDENCE);
            case UNKNOWN ->
                Set.of(
                        CodingDeliveryRequirement.WORKSPACE_CHANGE_OR_JUSTIFICATION,
                        CodingDeliveryRequirement.READ_ONLY_EVIDENCE);
        };
    }

    private record Resolution(CodingTaskIntent intent, CodingTaskIntentSource source, int confidencePercent) {}
}
