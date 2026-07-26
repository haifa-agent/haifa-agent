package io.haifa.agent.policy.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable, secret-free digest of the fixed public policy request fields. */
public final class PolicyRequestDigest {
    private PolicyRequestDigest() {}

    public static String compute(PolicyRequest request) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.subject().tenant().tenantId());
        append(canonical, request.subject().principal().principalType());
        append(canonical, request.subject().principal().principalId());
        append(canonical, request.subject().productId());
        append(canonical, request.context().projectRef().orElse(""));
        append(canonical, request.context().sessionRef().orElse(""));
        append(canonical, request.context().runRef().orElse(""));
        append(canonical, request.context().attemptRef().orElse(""));
        append(canonical, request.context().approvalMode().name());
        append(
                canonical,
                request.context().projectTrustRef().map(ProjectTrustRef::value).orElse(""));
        append(canonical, request.context().securityConfigurationDigest().orElse(""));
        append(canonical, request.action().capability());
        append(canonical, request.action().operation());
        append(canonical, request.resource().resourceType());
        append(canonical, request.resource().resourceRef());
        append(canonical, request.resource().resourceDigest().orElse(""));
        append(canonical, request.risk().level().name());
        request.risk().sideEffects().stream().map(Enum::name).sorted().forEach(value -> append(canonical, value));
        append(canonical, Boolean.toString(request.risk().credentialRequired()));
        append(canonical, request.risk().networkTargetSummary().orElse(""));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }
}
