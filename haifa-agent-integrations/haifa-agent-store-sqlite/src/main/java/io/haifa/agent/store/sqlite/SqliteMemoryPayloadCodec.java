package io.haifa.agent.store.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.memory.api.DerivedTextMemoryContent;
import io.haifa.agent.memory.api.DerivedTextType;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryCandidate;
import io.haifa.agent.memory.api.MemoryCandidateId;
import io.haifa.agent.memory.api.MemoryCandidateStatus;
import io.haifa.agent.memory.api.MemoryContent;
import io.haifa.agent.memory.api.MemoryEvidenceRef;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryRef;
import io.haifa.agent.memory.api.MemoryRetentionPolicy;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryScopeType;
import io.haifa.agent.memory.api.MemorySecurityLabel;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemorySourceType;
import io.haifa.agent.memory.api.MemoryStatus;
import io.haifa.agent.memory.api.MemoryVersion;
import io.haifa.agent.memory.api.MemoryVisibility;
import io.haifa.agent.memory.api.StructuredMemoryContent;
import io.haifa.agent.memory.api.TextMemoryContent;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Explicit, version-one plaintext codec. No domain type metadata is accepted from persisted JSON. */
final class SqliteMemoryPayloadCodec {
    private final ObjectMapper mapper =
            new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());

    byte[] encodeCandidate(MemoryCandidate value) {
        return write(new CandidatePayload(
                value.requestKey(),
                scope(value.scope()),
                value.kind().name(),
                value.subjectKey(),
                content(value.content()),
                value.sources().stream().map(this::source).toList(),
                value.evidence().stream().map(this::evidence).toList(),
                value.status().name(),
                names(value.securityLabels()),
                value.normalizedDigest(),
                value.policyVersion(),
                retention(value.retention()),
                value.createdAt(),
                value.updatedAt(),
                value.revision(),
                value.replacesMemoryRef().map(this::ref),
                value.conflictingMemoryRef().map(this::ref),
                value.approvedMemory().map(this::ref),
                value.dispositionReason()));
    }

    MemoryCandidate decodeCandidate(String id, byte[] bytes) {
        CandidatePayload p = read(bytes, CandidatePayload.class);
        return new MemoryCandidate(
                new MemoryCandidateId(id),
                p.requestKey(),
                scope(p.scope()),
                MemoryKind.valueOf(p.kind()),
                p.subjectKey(),
                content(p.content()),
                p.sources().stream().map(this::source).toList(),
                p.evidence().stream().map(this::evidence).toList(),
                MemoryCandidateStatus.valueOf(p.status()),
                labels(p.securityLabels()),
                p.normalizedDigest(),
                p.policyVersion(),
                retention(p.retention()),
                p.createdAt(),
                p.updatedAt(),
                p.revision(),
                p.replaces().map(this::ref),
                p.conflict().map(this::ref),
                p.approved().map(this::ref),
                p.dispositionReason());
    }

    byte[] encodeMemory(Memory value) {
        return write(new MemoryPayload(
                scope(value.scope()),
                value.kind().name(),
                value.subjectKey(),
                value.content().map(this::content),
                value.sources().stream().map(this::source).toList(),
                value.evidence().stream().map(this::evidence).toList(),
                value.status().name(),
                names(value.securityLabels()),
                value.normalizedDigest(),
                value.previousVersion().map(this::ref),
                value.invalidationReason(),
                value.replacedByMemoryRef().map(this::ref),
                retention(value.retention()),
                value.createdAt(),
                value.updatedAt()));
    }

    Memory decodeMemory(String id, long version, byte[] bytes) {
        MemoryPayload p = read(bytes, MemoryPayload.class);
        return new Memory(
                new MemoryId(id),
                new MemoryVersion(version),
                scope(p.scope()),
                MemoryKind.valueOf(p.kind()),
                p.subjectKey(),
                p.content().map(this::content),
                p.sources().stream().map(this::source).toList(),
                p.evidence().stream().map(this::evidence).toList(),
                MemoryStatus.valueOf(p.status()),
                labels(p.securityLabels()),
                p.normalizedDigest(),
                p.previous().map(this::ref),
                p.invalidationReason(),
                p.replacedBy().map(this::ref),
                retention(p.retention()),
                p.createdAt(),
                p.updatedAt());
    }

    String writeSafeAttributes(Map<String, String> attributes) {
        return new String(write(attributes), java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] write(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode Memory payload", exception);
        }
    }

    private <T> T read(byte[] bytes, Class<T> type) {
        try {
            return mapper.readValue(bytes, type);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to decode Memory payload", exception);
        }
    }

    private ScopePayload scope(MemoryScope v) {
        return new ScopePayload(
                v.tenant().tenantId(),
                v.owner().principalId(),
                v.owner().principalType(),
                v.type().name(),
                v.targetId(),
                v.visibility().name(),
                names(v.securityLabels()));
    }

    private MemoryScope scope(ScopePayload v) {
        return new MemoryScope(
                new TenantRef(v.tenant()),
                new PrincipalRef(v.owner(), v.ownerType()),
                MemoryScopeType.valueOf(v.type()),
                v.target(),
                MemoryVisibility.valueOf(v.visibility()),
                labels(v.labels()));
    }

    private ContentPayload content(MemoryContent value) {
        if (value instanceof TextMemoryContent text)
            return new ContentPayload("TEXT", text.text(), Map.of(), Optional.empty(), Optional.empty());
        if (value instanceof StructuredMemoryContent structured)
            return new ContentPayload("STRUCTURED", "", structured.values(), Optional.empty(), Optional.empty());
        DerivedTextMemoryContent derived = (DerivedTextMemoryContent) value;
        return new ContentPayload(
                "DERIVED",
                derived.text(),
                Map.of(),
                Optional.of(asset(derived.derivedAsset())),
                Optional.of(derived.type().name()));
    }

    private MemoryContent content(ContentPayload value) {
        return switch (value.type()) {
            case "TEXT" -> new TextMemoryContent(value.text());
            case "STRUCTURED" -> new StructuredMemoryContent(value.values());
            case "DERIVED" ->
                new DerivedTextMemoryContent(
                        asset(value.asset().orElseThrow()),
                        DerivedTextType.valueOf(value.derivedType().orElseThrow()),
                        value.text());
            default -> throw new IllegalStateException("Unsupported Memory content type");
        };
    }

    private SourcePayload source(MemorySourceRef value) {
        return new SourcePayload(
                value.type().name(), value.sourceId(), value.assetRef().map(this::asset));
    }

    private MemorySourceRef source(SourcePayload value) {
        return new MemorySourceRef(
                MemorySourceType.valueOf(value.type()),
                value.id(),
                value.asset().map(this::asset));
    }

    private EvidencePayload evidence(MemoryEvidenceRef value) {
        return new EvidencePayload(source(value.source()), value.contentDigest());
    }

    private MemoryEvidenceRef evidence(EvidencePayload value) {
        return new MemoryEvidenceRef(source(value.source()), value.digest());
    }

    private AssetPayload asset(AssetRef value) {
        return new AssetPayload(value.assetId(), value.mimeType(), value.filename());
    }

    private AssetRef asset(AssetPayload value) {
        return new AssetRef(value.id(), value.mimeType(), value.filename());
    }

    private RefPayload ref(MemoryRef value) {
        return new RefPayload(value.id().value(), value.version().value());
    }

    private MemoryRef ref(RefPayload value) {
        return new MemoryRef(new MemoryId(value.id()), new MemoryVersion(value.version()));
    }

    private RetentionPayload retention(MemoryRetentionPolicy value) {
        return new RetentionPayload(value.policyId(), value.expiresAt(), value.purgeAfterExpiry());
    }

    private MemoryRetentionPolicy retention(RetentionPayload value) {
        return new MemoryRetentionPolicy(value.policyId(), value.expiresAt(), value.purgeAfterExpiry());
    }

    private static List<String> names(Set<MemorySecurityLabel> values) {
        return values.stream().map(Enum::name).sorted().toList();
    }

    private static Set<MemorySecurityLabel> labels(List<String> values) {
        return values.stream()
                .map(MemorySecurityLabel::valueOf)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private record CandidatePayload(
            String requestKey,
            ScopePayload scope,
            String kind,
            String subjectKey,
            ContentPayload content,
            List<SourcePayload> sources,
            List<EvidencePayload> evidence,
            String status,
            List<String> securityLabels,
            String normalizedDigest,
            String policyVersion,
            RetentionPayload retention,
            Instant createdAt,
            Instant updatedAt,
            long revision,
            Optional<RefPayload> replaces,
            Optional<RefPayload> conflict,
            Optional<RefPayload> approved,
            Optional<String> dispositionReason) {}

    private record MemoryPayload(
            ScopePayload scope,
            String kind,
            String subjectKey,
            Optional<ContentPayload> content,
            List<SourcePayload> sources,
            List<EvidencePayload> evidence,
            String status,
            List<String> securityLabels,
            String normalizedDigest,
            Optional<RefPayload> previous,
            Optional<String> invalidationReason,
            Optional<RefPayload> replacedBy,
            RetentionPayload retention,
            Instant createdAt,
            Instant updatedAt) {}

    private record ScopePayload(
            String tenant,
            String owner,
            String ownerType,
            String type,
            String target,
            String visibility,
            List<String> labels) {}

    private record ContentPayload(
            String type,
            String text,
            Map<String, String> values,
            Optional<AssetPayload> asset,
            Optional<String> derivedType) {}

    private record SourcePayload(String type, String id, Optional<AssetPayload> asset) {}

    private record EvidencePayload(SourcePayload source, String digest) {}

    private record AssetPayload(String id, String mimeType, String filename) {}

    private record RefPayload(String id, long version) {}

    private record RetentionPayload(String policyId, Optional<Instant> expiresAt, boolean purgeAfterExpiry) {}
}
