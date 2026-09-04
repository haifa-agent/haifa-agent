package io.haifa.agent.model.core;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.ImageInputProfile;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelAuthenticationMethod;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelExecutionLimits;
import io.haifa.agent.model.api.ModelImageDetail;
import io.haifa.agent.model.api.ModelImageSource;
import io.haifa.agent.model.api.ModelInputModality;
import io.haifa.agent.model.api.ModelIoProfile;
import io.haifa.agent.model.api.ModelOutputModality;
import io.haifa.agent.model.api.ModelPartialOutputFailureBehavior;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ModelStreamingProfile;
import io.haifa.agent.model.api.ProviderStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict loader for explicitly listed, packaged static model catalog YAML resources. */
public final class ModelCatalogYamlLoader {
    public static final String ROOT_RESOURCE = "META-INF/haifa/model-catalog/catalog.yaml";
    private static final String ROOT_SCHEMA = "haifa.model-catalog/v1";
    private static final String PROVIDER_SCHEMA = "haifa.model-catalog-provider/v1";
    private static final String BINDING_SCHEMA = "haifa.model-catalog-binding/v1";
    private static final Pattern YAML_ANCHOR_OR_ALIAS =
            Pattern.compile("(?m)(?:^|[\\s:\\-\\[\\],])(?:&|\\*)[A-Za-z_][A-Za-z0-9_-]*");

    private final ModelCatalogResourceReader resources;
    private final Map<ApiStyleId, Set<String>> registeredDialects;
    private final Map<ModelProviderId, Set<ModelAuthenticationMethod>> registeredAuthenticationMethods;
    private final ObjectMapper yaml;

    public ModelCatalogYamlLoader(
            ModelCatalogResourceReader resources,
            Map<ApiStyleId, Set<String>> registeredDialects,
            Map<ModelProviderId, Set<ModelAuthenticationMethod>> registeredAuthenticationMethods) {
        this.resources = Objects.requireNonNull(resources, "resources must not be null");
        this.registeredDialects = normalizeDialects(registeredDialects);
        this.registeredAuthenticationMethods = normalizeAuthenticationMethods(registeredAuthenticationMethods);
        yaml = new ObjectMapper(YAMLFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
    }

    public static ModelCatalogYamlLoader fromClasspath(
            ClassLoader classLoader,
            Map<ApiStyleId, Set<String>> registeredDialects,
            Map<ModelProviderId, Set<ModelAuthenticationMethod>> registeredAuthenticationMethods) {
        ClassLoader loader = Objects.requireNonNull(classLoader, "classLoader must not be null");
        return new ModelCatalogYamlLoader(
                resource -> {
                    InputStream input = loader.getResourceAsStream(resource);
                    if (input == null) throw new IllegalArgumentException("missing catalog resource: " + resource);
                    return input;
                },
                registeredDialects,
                registeredAuthenticationMethods);
    }

    public ModelCatalogManifest load() {
        Map<String, Object> root = document(ROOT_RESOURCE, "catalog");
        requireSchema(root, ROOT_SCHEMA, ROOT_RESOURCE);
        requireFields(root, Set.of("schemaVersion", "providers"), ROOT_RESOURCE);
        List<ModelCatalogProvider> providers = new ArrayList<>();
        for (Map<String, Object> entry : objectList(root, "providers", ROOT_RESOURCE)) {
            requireFields(entry, Set.of("resource"), ROOT_RESOURCE + " provider entry");
            String resource = rootProviderResource(requiredText(entry, "resource", ROOT_RESOURCE));
            providers.add(loadProvider(resource));
        }
        return new ModelCatalogManifest(providers);
    }

    private ModelCatalogProvider loadProvider(String resource) {
        Map<String, Object> provider = document(resource, "provider");
        requireSchema(provider, PROVIDER_SCHEMA, resource);
        requireFields(
                provider,
                Set.of(
                        "schemaVersion",
                        "providerId",
                        "version",
                        "displayName",
                        "status",
                        "authenticationMethods",
                        "bindings"),
                resource);
        ModelProviderId providerId = new ModelProviderId(requiredText(provider, "providerId", resource));
        Set<ModelAuthenticationMethod> authenticationMethods = enumSet(
                stringList(provider, "authenticationMethods", resource),
                ModelAuthenticationMethod.class,
                resource,
                "authenticationMethods");
        Set<ModelAuthenticationMethod> registered = registeredAuthenticationMethods.get(providerId);
        if (registered == null || !registered.containsAll(authenticationMethods)) {
            throw new IllegalArgumentException("authentication method is not registered for provider: " + providerId);
        }
        List<ModelCatalogBinding> bindings = new ArrayList<>();
        for (Map<String, Object> entry : objectList(provider, "bindings", resource)) {
            requireFields(entry, Set.of("resource"), resource + " binding entry");
            bindings.add(
                    loadBinding(providerId, childBindingResource(resource, requiredText(entry, "resource", resource))));
        }
        return new ModelCatalogProvider(
                providerId,
                requiredText(provider, "version", resource),
                requiredText(provider, "displayName", resource),
                enumValue(requiredText(provider, "status", resource), ProviderStatus.class, resource, "status"),
                authenticationMethods,
                bindings);
    }

    private ModelCatalogBinding loadBinding(ModelProviderId providerId, String resource) {
        Map<String, Object> binding = document(resource, "binding");
        requireSchema(binding, BINDING_SCHEMA, resource);
        requireFields(
                binding,
                Set.of(
                        "schemaVersion",
                        "bindingId",
                        "version",
                        "providerModelId",
                        "displayName",
                        "status",
                        "apiStyle",
                        "dialect",
                        "capabilities",
                        "profile"),
                resource);
        ApiStyleId style = new ApiStyleId(requiredText(binding, "apiStyle", resource));
        String dialect = requiredText(binding, "dialect", resource);
        Set<String> registered = registeredDialects.get(style);
        if (registered == null || !registered.contains(dialect)) {
            throw new IllegalArgumentException("dialect is not registered for API style: " + style + "/" + dialect);
        }
        Set<ModelCapability> capabilities =
                enumSet(stringList(binding, "capabilities", resource), ModelCapability.class, resource, "capabilities");
        ModelBindingProfile profile = profile(
                new ModelDefinitionId(requiredText(binding, "bindingId", resource)),
                style,
                capabilities,
                object(binding, "profile", resource),
                resource);
        if (profile.status() != ModelProfileStatus.VERIFIED) {
            throw new IllegalArgumentException("catalog binding profile must be VERIFIED: " + profile.bindingId());
        }
        ModelDefinition definition = new ModelDefinition(
                profile.bindingId(),
                requiredText(binding, "version", resource),
                providerId,
                requiredText(binding, "providerModelId", resource),
                requiredText(binding, "displayName", resource),
                enumValue(requiredText(binding, "status", resource), ModelStatus.class, resource, "status"),
                capabilities,
                profile.contextWindowTokens(),
                profile.maximumOutputTokens(),
                Map.of(),
                Map.of(),
                style);
        return new ModelCatalogBinding(definition, new ModelApiBindingDefinition(style, dialect), profile);
    }

    private static ModelBindingProfile profile(
            ModelDefinitionId bindingId,
            ApiStyleId style,
            Set<ModelCapability> capabilities,
            Map<String, Object> profile,
            String resource) {
        String location = resource + " profile";
        requireKeys(
                profile,
                Set.of(
                        "version",
                        "reasoningBehavior",
                        "allowedReasoningModes",
                        "allowedReasoningEfforts",
                        "maximumReasoningTokens",
                        "minimumOutputTokens",
                        "maximumOutputTokens",
                        "contextWindowTokens",
                        "toolReasoningContinuationRequired",
                        "nativeStreaming",
                        "usageStreaming",
                        "reasoningStreaming",
                        "partialOutputFailureBehavior",
                        "inputModalities",
                        "outputModalities",
                        "imageInput",
                        "status",
                        "lastVerifiedOn"),
                location);
        OptionalLong maximumReasoningTokens = optionalPositiveLong(profile.get("maximumReasoningTokens"), location);
        ModelExecutionLimits limits = new ModelExecutionLimits(
                positiveInt(profile, "contextWindowTokens", location),
                positiveInt(profile, "minimumOutputTokens", location),
                positiveInt(profile, "maximumOutputTokens", location));
        Set<ModelInputModality> inputModalities = enumSet(
                stringList(profile, "inputModalities", location),
                ModelInputModality.class,
                location,
                "inputModalities");
        Set<ModelOutputModality> outputModalities = enumSet(
                stringList(profile, "outputModalities", location),
                ModelOutputModality.class,
                location,
                "outputModalities");
        ModelIoProfile ioProfile = ioProfile(profile, inputModalities, outputModalities, location);
        return ModelBindingProfile.create(
                bindingId,
                style,
                requiredText(profile, "version", location),
                capabilities,
                enumValue(
                        requiredText(profile, "reasoningBehavior", location),
                        ModelReasoningBehavior.class,
                        location,
                        "reasoningBehavior"),
                enumSet(
                        stringList(profile, "allowedReasoningModes", location),
                        ModelReasoningMode.class,
                        location,
                        "allowedReasoningModes"),
                enumSet(
                        stringList(profile, "allowedReasoningEfforts", location),
                        ModelReasoningEffort.class,
                        location,
                        "allowedReasoningEfforts"),
                maximumReasoningTokens,
                limits,
                requiredBoolean(profile, "toolReasoningContinuationRequired", location),
                new ModelStreamingProfile(
                        requiredBoolean(profile, "nativeStreaming", location),
                        requiredBoolean(profile, "usageStreaming", location),
                        requiredBoolean(profile, "reasoningStreaming", location),
                        enumValue(
                                requiredText(profile, "partialOutputFailureBehavior", location),
                                ModelPartialOutputFailureBehavior.class,
                                location,
                                "partialOutputFailureBehavior")),
                ioProfile,
                enumValue(requiredText(profile, "status", location), ModelProfileStatus.class, location, "status"),
                LocalDate.parse(requiredText(profile, "lastVerifiedOn", location)));
    }

    private static ModelIoProfile ioProfile(
            Map<String, Object> profile,
            Set<ModelInputModality> inputModalities,
            Set<ModelOutputModality> outputModalities,
            String location) {
        Object imageInput = profile.get("imageInput");
        if (imageInput == null) {
            if (!inputModalities.equals(Set.of(ModelInputModality.TEXT))
                    || !outputModalities.equals(Set.of(ModelOutputModality.TEXT))) {
                throw new IllegalArgumentException("image input profile is required for non-text IO: " + location);
            }
            return ModelIoProfile.textOnly();
        }
        Map<String, Object> image = map(imageInput, location + " imageInput");
        requireFields(
                image,
                Set.of(
                        "allowedSources",
                        "supportedMediaTypes",
                        "maxImagesPerRequest",
                        "maxBytesPerItem",
                        "maxTotalBytes",
                        "maxUrlCharacters",
                        "detailSupported",
                        "allowedDetails"),
                location + " imageInput");
        if (!inputModalities.contains(ModelInputModality.IMAGE)) {
            throw new IllegalArgumentException("image input profile requires IMAGE modality: " + location);
        }
        ImageInputProfile imageProfile = new ImageInputProfile(
                enumSet(
                        stringList(image, "allowedSources", location),
                        ModelImageSource.class,
                        location,
                        "allowedSources"),
                Set.copyOf(stringList(image, "supportedMediaTypes", location)),
                positiveInt(image, "maxImagesPerRequest", location),
                positiveLong(image, "maxBytesPerItem", location),
                positiveLong(image, "maxTotalBytes", location),
                positiveInt(image, "maxUrlCharacters", location),
                requiredBoolean(image, "detailSupported", location),
                enumSet(
                        stringList(image, "allowedDetails", location),
                        ModelImageDetail.class,
                        location,
                        "allowedDetails"));
        return new ModelIoProfile(inputModalities, outputModalities, Optional.of(imageProfile));
    }

    private Map<String, Object> document(String resource, String type) {
        try (InputStream input = resources.open(resource)) {
            if (input == null) throw new IllegalArgumentException("missing catalog resource: " + resource);
            byte[] content = input.readAllBytes();
            String text = new String(content, StandardCharsets.UTF_8);
            if (YAML_ANCHOR_OR_ALIAS.matcher(text).find()) {
                throw new IllegalArgumentException("YAML anchors and aliases are not allowed: " + resource);
            }
            Object parsed = yaml.readValue(content, Object.class);
            return map(parsed, resource + " " + type);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid catalog YAML resource: " + resource, exception);
        }
    }

    private static Map<ApiStyleId, Set<String>> normalizeDialects(Map<ApiStyleId, Set<String>> dialects) {
        Objects.requireNonNull(dialects, "registeredDialects must not be null");
        LinkedHashMap<ApiStyleId, Set<String>> normalized = new LinkedHashMap<>();
        dialects.forEach((style, values) -> {
            ApiStyleId key = Objects.requireNonNull(style, "registered dialect style must not be null");
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (String value : Objects.requireNonNull(values, "registered dialect names must not be null")) {
                String name = Objects.requireNonNull(value, "registered dialect name must not be null")
                        .trim();
                if (!name.matches("[a-z][a-z0-9-]{0,127}")) {
                    throw new IllegalArgumentException("registered dialect name is invalid: " + name);
                }
                names.add(name);
            }
            if (names.isEmpty()) throw new IllegalArgumentException("registered dialect names must not be empty");
            if (normalized.putIfAbsent(key, Set.copyOf(names)) != null) {
                throw new IllegalArgumentException("duplicate registered dialect style: " + key);
            }
        });
        return Map.copyOf(normalized);
    }

    private static Map<ModelProviderId, Set<ModelAuthenticationMethod>> normalizeAuthenticationMethods(
            Map<ModelProviderId, Set<ModelAuthenticationMethod>> methods) {
        Objects.requireNonNull(methods, "registeredAuthenticationMethods must not be null");
        LinkedHashMap<ModelProviderId, Set<ModelAuthenticationMethod>> normalized = new LinkedHashMap<>();
        methods.forEach((provider, values) -> {
            ModelProviderId key =
                    Objects.requireNonNull(provider, "registered authentication provider must not be null");
            Set<ModelAuthenticationMethod> registered =
                    Set.copyOf(Objects.requireNonNull(values, "registered authentication methods must not be null"));
            if (registered.isEmpty())
                throw new IllegalArgumentException("registered authentication methods must not be empty");
            if (normalized.putIfAbsent(key, registered) != null) {
                throw new IllegalArgumentException("duplicate registered authentication provider: " + key);
            }
        });
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> map(Object value, String location) {
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("expected mapping at " + location);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException("mapping key must be non-blank text at " + location);
            }
            if ("<<".equals(key)) throw new IllegalArgumentException("YAML merge is not allowed at " + location);
            if (result.putIfAbsent(key, entry.getValue()) != null) {
                throw new IllegalArgumentException("duplicate mapping key at " + location + ": " + key);
            }
        }
        return result;
    }

    private static Map<String, Object> object(Map<String, Object> parent, String field, String location) {
        return map(required(parent, field, location), location + " " + field);
    }

    private static List<Map<String, Object>> objectList(Map<String, Object> parent, String field, String location) {
        Object value = required(parent, field, location);
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("field must be a non-empty list at " + location + ": " + field);
        }
        List<Map<String, Object>> objects = new ArrayList<>();
        for (Object item : values) objects.add(map(item, location + " " + field));
        return List.copyOf(objects);
    }

    private static List<String> stringList(Map<String, Object> parent, String field, String location) {
        Object value = required(parent, field, location);
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("field must be a list at " + location + ": " + field);
        }
        List<String> strings = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("list items must be non-blank text at " + location + ": " + field);
            }
            strings.add(text.trim());
        }
        return List.copyOf(strings);
    }

    private static void requireSchema(Map<String, Object> document, String schema, String location) {
        if (!schema.equals(requiredText(document, "schemaVersion", location))) {
            throw new IllegalArgumentException("unsupported catalog schema at " + location);
        }
    }

    private static void requireFields(Map<String, Object> document, Set<String> expected, String location) {
        requireKeys(document, expected, location);
        for (String field : expected) required(document, field, location);
    }

    private static void requireKeys(Map<String, Object> document, Set<String> expected, String location) {
        for (String field : document.keySet()) {
            if (!expected.contains(field))
                throw new IllegalArgumentException("unknown field at " + location + ": " + field);
        }
        for (String field : expected) {
            if (!document.containsKey(field))
                throw new IllegalArgumentException("missing field at " + location + ": " + field);
        }
    }

    private static Object required(Map<String, Object> values, String field, String location) {
        if (!values.containsKey(field))
            throw new IllegalArgumentException("missing field at " + location + ": " + field);
        Object value = values.get(field);
        if (value == null) throw new IllegalArgumentException("field must not be null at " + location + ": " + field);
        return value;
    }

    private static String requiredText(Map<String, Object> values, String field, String location) {
        Object value = required(values, field, location);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("field must be non-blank text at " + location + ": " + field);
        }
        return text.trim();
    }

    private static boolean requiredBoolean(Map<String, Object> values, String field, String location) {
        Object value = required(values, field, location);
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("field must be boolean at " + location + ": " + field);
        }
        return bool;
    }

    private static int positiveInt(Map<String, Object> values, String field, String location) {
        Object value = required(values, field, location);
        if (!(value instanceof Number number) || number.longValue() < 1 || number.longValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("field must be a positive integer at " + location + ": " + field);
        }
        return number.intValue();
    }

    private static long positiveLong(Map<String, Object> values, String field, String location) {
        Object value = required(values, field, location);
        if (!(value instanceof Number number) || number.longValue() < 1) {
            throw new IllegalArgumentException("field must be a positive integer at " + location + ": " + field);
        }
        return number.longValue();
    }

    private static OptionalLong optionalPositiveLong(Object value, String location) {
        if (value == null) return OptionalLong.empty();
        if (!(value instanceof Number number) || number.longValue() < 1) {
            throw new IllegalArgumentException("maximumReasoningTokens must be a positive integer at " + location);
        }
        return OptionalLong.of(number.longValue());
    }

    private static <E extends Enum<E>> Set<E> enumSet(
            List<String> values, Class<E> type, String location, String field) {
        EnumSet<E> result = EnumSet.noneOf(type);
        for (String value : values) result.add(enumValue(value, type, location, field));
        return Set.copyOf(result);
    }

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type, String location, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid " + field + " at " + location + ": " + value, exception);
        }
    }

    private static String rootProviderResource(String resource) {
        if (!resource.matches("providers/[a-z][a-z0-9-]{0,63}/provider\\.yaml")) {
            throw new IllegalArgumentException(
                    "catalog provider resource must be explicit and normalized: " + resource);
        }
        return ROOT_RESOURCE.substring(0, ROOT_RESOURCE.lastIndexOf('/') + 1) + resource;
    }

    private static String childBindingResource(String providerResource, String resource) {
        if (!resource.matches("bindings/[a-z][a-z0-9-]{0,127}\\.yaml")) {
            throw new IllegalArgumentException(
                    "provider binding resource must be explicit and normalized: " + resource);
        }
        return providerResource.substring(0, providerResource.lastIndexOf('/') + 1) + resource;
    }
}
