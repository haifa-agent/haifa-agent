package io.haifa.agent.model.api;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.StructuredOutputRequirement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete provider-neutral input for one physical chat invocation. */
public record AgentChatRequest(
        ModelCallId callId,
        ModelRequestId requestId,
        AgentRunId runId,
        int iteration,
        int attempt,
        ResolvedModelSnapshot model,
        List<ModelMessage> messages,
        List<ModelToolSpecification> tools,
        int maxOutputTokens,
        Duration timeout,
        Map<String, Object> options,
        java.util.Optional<StructuredOutputRequirement> structuredOutput) {

    public AgentChatRequest(
            ModelCallId callId,
            AgentRunId runId,
            int iteration,
            int attempt,
            ResolvedModelSnapshot model,
            List<ModelMessage> messages,
            List<ModelToolSpecification> tools,
            int maxOutputTokens,
            Duration timeout,
            Map<String, Object> options,
            java.util.Optional<StructuredOutputRequirement> structuredOutput) {
        this(
                callId,
                new ModelRequestId(callId.value()),
                runId,
                iteration,
                attempt,
                model,
                messages,
                tools,
                maxOutputTokens,
                timeout,
                options,
                structuredOutput);
    }

    public AgentChatRequest(
            ModelCallId callId,
            AgentRunId runId,
            int iteration,
            int attempt,
            ResolvedModelSnapshot model,
            List<ModelMessage> messages,
            List<ModelToolSpecification> tools,
            int maxOutputTokens,
            Duration timeout,
            Map<String, Object> options) {
        this(
                callId,
                new ModelRequestId(callId.value()),
                runId,
                iteration,
                attempt,
                model,
                messages,
                tools,
                maxOutputTokens,
                timeout,
                options,
                java.util.Optional.empty());
    }

    public AgentChatRequest {
        callId = Objects.requireNonNull(callId, "callId must not be null");
        requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        if (iteration < 1 || attempt < 1) throw new IllegalArgumentException("iteration and attempt must be positive");
        model = Objects.requireNonNull(model, "model must not be null");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        if (messages.isEmpty()) throw new IllegalArgumentException("messages must not be empty");

        List<ModelImagePart> allImages =
                messages.stream().flatMap(message -> message.images().stream()).toList();
        if (!allImages.isEmpty()) {
            java.util.Optional<ImageInputProfile> imageProfileOpt = model.frozenImageInputProfile();
            if (imageProfileOpt.isEmpty()) {
                throw new IllegalArgumentException("selected model does not support image input");
            }
            ImageInputProfile imageProfile = imageProfileOpt.get();
            if (allImages.size() > imageProfile.maxImagesPerRequest()) {
                throw new IllegalArgumentException("number of images in chat request (" + allImages.size()
                        + ") exceeds maximum allowed (" + imageProfile.maxImagesPerRequest() + ")");
            }
            long totalBytes = 0;
            for (ModelImagePart image : allImages) {
                if (image instanceof ImageUrlPart remote) {
                    if (!imageProfile.allowedSources().contains(ModelImageSource.URL)
                            || !model.capabilities().contains(ModelCapability.IMAGE_URL_INPUT)) {
                        throw new IllegalArgumentException("selected model does not support image URL input");
                    }
                    if (remote.url().toASCIIString().length() > imageProfile.maxUrlCharacters()) {
                        throw new IllegalArgumentException("image URL length ("
                                + remote.url().toASCIIString().length() + ") exceeds maximum allowed ("
                                + imageProfile.maxUrlCharacters() + ")");
                    }
                } else if (image instanceof ImageDataPart data) {
                    if (!imageProfile.allowedSources().contains(ModelImageSource.UPLOAD)
                            || !model.capabilities().contains(ModelCapability.IMAGE_UPLOAD_INPUT)) {
                        throw new IllegalArgumentException("selected model does not support uploaded image input");
                    }
                    if (!imageProfile.supportedMediaTypes().contains(data.mediaType())) {
                        throw new IllegalArgumentException(
                                "image media type '" + data.mediaType() + "' is not supported by the selected model");
                    }
                    if (data.sizeBytes() > imageProfile.maxBytesPerItem()) {
                        throw new IllegalArgumentException("image item size (" + data.sizeBytes()
                                + " bytes) exceeds maximum allowed (" + imageProfile.maxBytesPerItem() + " bytes)");
                    }
                    totalBytes += data.sizeBytes();
                }
            }
            if (totalBytes > imageProfile.maxTotalBytes()) {
                throw new IllegalArgumentException("total image data bytes (" + totalBytes
                        + ") exceeds request maximum (" + imageProfile.maxTotalBytes() + ")");
            }
        }

        if (messages.stream().anyMatch(message -> !message.audios().isEmpty())
                && !model.capabilities().contains(ModelCapability.AUDIO_INPUT)) {
            throw new IllegalArgumentException("selected model does not declare audio input capability");
        }
        tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
        if (maxOutputTokens < 1) throw new IllegalArgumentException("maxOutputTokens must be positive");
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        options = ModelValues.map(options, "options");
        structuredOutput = Objects.requireNonNullElse(structuredOutput, java.util.Optional.empty());
    }
}
