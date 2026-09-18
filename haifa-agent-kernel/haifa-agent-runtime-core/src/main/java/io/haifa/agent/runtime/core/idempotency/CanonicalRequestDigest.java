package io.haifa.agent.runtime.core.idempotency;

import io.haifa.agent.common.time.TimePrecision;
import io.haifa.agent.core.content.ArtifactRefPart;
import io.haifa.agent.core.content.AssetRefPart;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.ImageUrlContentPart;
import io.haifa.agent.core.content.StoredAudioContentPart;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.RunInputSubmission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable length-prefixed request digest for in-memory idempotency bindings. */
public final class CanonicalRequestDigest {
    private CanonicalRequestDigest() {}

    public static String agentRun(AgentRunRequest request) {
        Digester digest = new Digester();
        digest.add("agent-run-start-v1");
        digest.add(request.agentDefinitionId().value());
        digest.add(request.requestedDefinitionVersion().map(Object::toString).orElse(""));
        digest.add(request.productProfileId());
        digest.add(request.sessionId().value());
        digest.add(request.project().map(project -> project.projectId()).orElse(""));
        digest.add(request.objective());
        addContents(digest, request.inputs());
        digest.add(request.overrides().schemaId());
        digest.add(request.overrides().schemaVersion());
        request.overrides().values().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    digest.add(entry.getKey());
                    digest.add(entry.getValue().getClass().getName());
                    digest.add(entry.getValue().toString());
                });
        return digest.finish();
    }

    public static String interactionResponse(InteractionResponseSubmission submission) {
        Digester digest = new Digester();
        digest.add("interaction-response-v1");
        digest.add(submission.responseId().value());
        digest.add(submission.requestId().value());
        digest.add(submission.runId().value());
        digest.add(Long.toString(submission.expectedRevision()));
        digest.add(submission.action().value());
        addContents(digest, submission.inputs());
        digest.add(TimePrecision.toMilliseconds(submission.respondedAt()).toString());
        return digest.finish();
    }

    public static String runInput(RunInputSubmission submission) {
        Digester digest = new Digester();
        digest.add("run-input-v1");
        digest.add(submission.inputId().value());
        digest.add(submission.runId().value());
        digest.add(
                submission.expectedRunVersion().isPresent()
                        ? Long.toString(submission.expectedRunVersion().getAsLong())
                        : "");
        addContents(digest, submission.contents());
        digest.add(TimePrecision.toMilliseconds(submission.submittedAt()).toString());
        return digest.finish();
    }

    private static void addContents(Digester digest, java.util.List<ContentPart> contents) {
        digest.add(Integer.toString(contents.size()));
        for (ContentPart content : contents) {
            digest.add(content.contentType());
            if (content instanceof TextPart text) {
                digest.add(text.format());
                digest.add(text.text());
            } else if (content instanceof AssetRefPart asset) {
                digest.add(asset.asset().assetId());
                digest.add(asset.asset().mimeType());
                digest.add(asset.asset().filename());
            } else if (content instanceof ArtifactRefPart artifact) {
                digest.add(artifact.artifact().artifactId());
                digest.add(artifact.artifact().artifactType());
                digest.add(artifact.artifact().version());
                digest.add(artifact.artifact().title());
                digest.add(artifact.summary());
            } else if (content instanceof ImageUrlContentPart image) {
                digest.add(image.url().toASCIIString());
            } else if (content instanceof StoredImageContentPart image) {
                digest.add(image.storeId());
                digest.add(image.imageId());
                digest.add(image.mediaType());
                digest.add(Long.toString(image.sizeBytes()));
                digest.add(image.sha256());
                digest.add(image.originalFilename());
            } else if (content instanceof StoredAudioContentPart audio) {
                digest.add(audio.storeId());
                digest.add(audio.audioId());
                digest.add(audio.mediaType());
                digest.add(Long.toString(audio.sizeBytes()));
                digest.add(audio.sha256());
                digest.add(audio.originalFilename());
            } else {
                throw new IllegalArgumentException("tool protocol content is not valid public input");
            }
        }
    }

    private static final class Digester {
        private final MessageDigest digest;

        private Digester() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        private void add(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
        }

        private String finish() {
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
    }
}
