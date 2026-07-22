package io.haifa.agent.tool.core;

import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSchemaValidationError;
import io.haifa.agent.tool.api.ToolSchemaValidationResult;
import io.haifa.agent.tool.api.ToolSchemaValidator;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded validator for the Draft 2020-12 keywords used by platform tool schemas. */
public final class JsonSchema202012Validator implements ToolSchemaValidator {
    private static final int MAX_DEPTH = 64;
    private static final int DEFAULT_MAX_INSTANCE_NODES = 10_000;
    private static final int MAX_STRING_CHARS = 1_000_000;
    private final int maxErrors;
    private final int maxInstanceNodes;
    private final long maxValidationNanos;
    private final ThreadLocal<ValidationBudget> activeBudget = new ThreadLocal<>();

    public JsonSchema202012Validator() {
        this(20, DEFAULT_MAX_INSTANCE_NODES, Duration.ofMillis(100));
    }

    public JsonSchema202012Validator(int maxErrors) {
        this(maxErrors, DEFAULT_MAX_INSTANCE_NODES, Duration.ofMillis(100));
    }

    public JsonSchema202012Validator(int maxErrors, int maxInstanceNodes, Duration maxValidationTime) {
        if (maxErrors < 1 || maxErrors > 100) {
            throw new IllegalArgumentException("maxErrors must be between 1 and 100");
        }
        if (maxInstanceNodes < 1 || maxInstanceNodes > 1_000_000) {
            throw new IllegalArgumentException("maxInstanceNodes must be between 1 and 1000000");
        }
        Objects.requireNonNull(maxValidationTime, "maxValidationTime");
        if (maxValidationTime.isZero() || maxValidationTime.isNegative()) {
            throw new IllegalArgumentException("maxValidationTime must be positive");
        }
        this.maxErrors = maxErrors;
        this.maxInstanceNodes = maxInstanceNodes;
        this.maxValidationNanos = maxValidationTime.toNanos();
    }

    @Override
    public ToolSchemaValidationResult validate(ToolSchema schema, Map<String, Object> instance) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(instance, "instance");
        var errors = new ArrayList<ToolSchemaValidationError>();
        activeBudget.set(new ValidationBudget(maxInstanceNodes, maxValidationNanos));
        try {
            validateNode(schema.document(), instance, "$", schema.document(), errors, 0);
            budget().check();
        } catch (ValidationLimitReached limit) {
            errors.clear();
            error(errors, "$", "limit", "schema validation resource limit exceeded");
        } finally {
            activeBudget.remove();
        }
        return new ToolSchemaValidationResult(errors);
    }

    @SuppressWarnings("unchecked")
    private void validateNode(
            Object schemaValue,
            Object instance,
            String path,
            Map<String, Object> root,
            List<ToolSchemaValidationError> errors,
            int depth) {
        if (errors.size() >= maxErrors) return;
        budget().consume();
        if (depth > MAX_DEPTH) {
            error(errors, path, "depth", "schema validation depth exceeded");
            return;
        }
        if (schemaValue instanceof Boolean booleanSchema) {
            if (!booleanSchema) error(errors, path, "falseSchema", "value is rejected by the schema");
            return;
        }
        if (!(schemaValue instanceof Map<?, ?> rawSchema)) {
            error(errors, path, "schema", "schema node must be an object or boolean");
            return;
        }
        Map<String, Object> schema = (Map<String, Object>) rawSchema;
        Object reference = schema.get("$ref");
        if (reference instanceof String text) {
            Object resolved = resolveLocalReference(root, text);
            if (resolved == null) error(errors, path, "$ref", "local reference cannot be resolved");
            else validateNode(resolved, instance, path, root, errors, depth + 1);
            return;
        }
        validateCompositions(schema, instance, path, root, errors, depth);
        validateType(schema, instance, path, errors);
        validateEnumAndConst(schema, instance, path, errors);
        if (instance instanceof Map<?, ?> object) {
            validateObject(schema, (Map<String, Object>) object, path, root, errors, depth);
        } else if (instance instanceof List<?> array) {
            validateArray(schema, array, path, root, errors, depth);
        } else if (instance instanceof String string) {
            validateString(schema, string, path, errors);
        } else if (instance instanceof Number number) {
            validateNumber(schema, number, path, errors);
        }
    }

    private void validateCompositions(
            Map<String, Object> schema,
            Object instance,
            String path,
            Map<String, Object> root,
            List<ToolSchemaValidationError> errors,
            int depth) {
        composition(schema, "allOf", instance, path, root, errors, depth, -1);
        composition(schema, "anyOf", instance, path, root, errors, depth, 1);
        composition(schema, "oneOf", instance, path, root, errors, depth, 2);
    }

    private void composition(
            Map<String, Object> schema,
            String keyword,
            Object instance,
            String path,
            Map<String, Object> root,
            List<ToolSchemaValidationError> errors,
            int depth,
            int expected) {
        if (!(schema.get(keyword) instanceof List<?> branches)) return;
        if (expected == -1) {
            branches.forEach(branch -> validateNode(branch, instance, path, root, errors, depth + 1));
            return;
        }
        int validBranches = 0;
        for (Object branch : branches) {
            var branchErrors = new ArrayList<ToolSchemaValidationError>();
            validateNode(branch, instance, path, root, branchErrors, depth + 1);
            if (branchErrors.isEmpty()) validBranches++;
        }
        if ((expected == 1 && validBranches == 0) || (expected == 2 && validBranches != 1)) {
            error(
                    errors,
                    path,
                    keyword,
                    expected == 1 ? "value must match at least one branch" : "value must match exactly one branch");
        }
    }

    private void validateType(
            Map<String, Object> schema, Object instance, String path, List<ToolSchemaValidationError> errors) {
        Object declared = schema.get("type");
        if (declared == null) return;
        boolean matches = declared instanceof String type
                ? matchesType(type, instance)
                : declared instanceof Collection<?> types
                        && types.stream().anyMatch(type -> type instanceof String text && matchesType(text, instance));
        if (!matches) error(errors, path, "type", "value does not match the declared type");
    }

    private static boolean matchesType(String type, Object value) {
        return switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" ->
                value instanceof Byte
                        || value instanceof Short
                        || value instanceof Integer
                        || value instanceof Long
                        || value instanceof java.math.BigInteger;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> false;
        };
    }

    private void validateEnumAndConst(
            Map<String, Object> schema, Object instance, String path, List<ToolSchemaValidationError> errors) {
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(instance)) {
            error(errors, path, "enum", "value is not one of the allowed values");
        }
        if (schema.containsKey("const") && !Objects.equals(schema.get("const"), instance)) {
            error(errors, path, "const", "value does not match the required constant");
        }
    }

    @SuppressWarnings("unchecked")
    private void validateObject(
            Map<String, Object> schema,
            Map<String, Object> instance,
            String path,
            Map<String, Object> root,
            List<ToolSchemaValidationError> errors,
            int depth) {
        if (schema.get("required") instanceof List<?> required) {
            required.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(key -> !instance.containsKey(key))
                    .forEach(
                            key -> error(errors, path + "/" + escape(key), "required", "required property is missing"));
        }
        Map<String, Object> properties =
                schema.get("properties") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        instance.forEach((key, value) -> {
            budget().consume();
            Object propertySchema = properties.get(key);
            if (propertySchema != null) {
                validateNode(propertySchema, value, path + "/" + escape(key), root, errors, depth + 1);
            } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                error(errors, path + "/" + escape(key), "additionalProperties", "additional property is not allowed");
            } else if (schema.get("additionalProperties") instanceof Map<?, ?> additional) {
                validateNode(additional, value, path + "/" + escape(key), root, errors, depth + 1);
            }
        });
        compareSize(schema.get("minProperties"), instance.size(), true, path, "minProperties", errors);
        compareSize(schema.get("maxProperties"), instance.size(), false, path, "maxProperties", errors);
    }

    private void validateArray(
            Map<String, Object> schema,
            List<?> instance,
            String path,
            Map<String, Object> root,
            List<ToolSchemaValidationError> errors,
            int depth) {
        if (instance.size() > maxInstanceNodes) throw new ValidationLimitReached();
        if (schema.containsKey("items")) {
            for (int index = 0; index < instance.size(); index++) {
                validateNode(schema.get("items"), instance.get(index), path + "/" + index, root, errors, depth + 1);
            }
        }
        compareSize(schema.get("minItems"), instance.size(), true, path, "minItems", errors);
        compareSize(schema.get("maxItems"), instance.size(), false, path, "maxItems", errors);
        if (Boolean.TRUE.equals(schema.get("uniqueItems"))
                && instance.stream().distinct().count() != instance.size()) {
            error(errors, path, "uniqueItems", "array items must be unique");
        }
    }

    private void validateString(
            Map<String, Object> schema, String instance, String path, List<ToolSchemaValidationError> errors) {
        if (instance.length() > MAX_STRING_CHARS) throw new ValidationLimitReached();
        compareSize(
                schema.get("minLength"),
                instance.codePointCount(0, instance.length()),
                true,
                path,
                "minLength",
                errors);
        compareSize(
                schema.get("maxLength"),
                instance.codePointCount(0, instance.length()),
                false,
                path,
                "maxLength",
                errors);
    }

    private void validateNumber(
            Map<String, Object> schema, Number instance, String path, List<ToolSchemaValidationError> errors) {
        BigDecimal value = new BigDecimal(instance.toString());
        compareNumber(schema.get("minimum"), value, true, path, "minimum", errors);
        compareNumber(schema.get("maximum"), value, false, path, "maximum", errors);
        if (schema.get("exclusiveMinimum") instanceof Number minimum
                && value.compareTo(new BigDecimal(minimum.toString())) <= 0) {
            error(errors, path, "exclusiveMinimum", "number must be greater than the exclusive minimum");
        }
        if (schema.get("exclusiveMaximum") instanceof Number maximum
                && value.compareTo(new BigDecimal(maximum.toString())) >= 0) {
            error(errors, path, "exclusiveMaximum", "number must be less than the exclusive maximum");
        }
    }

    private void compareNumber(
            Object boundary,
            BigDecimal value,
            boolean minimum,
            String path,
            String keyword,
            List<ToolSchemaValidationError> errors) {
        if (!(boundary instanceof Number number)) return;
        int comparison = value.compareTo(new BigDecimal(number.toString()));
        if ((minimum && comparison < 0) || (!minimum && comparison > 0)) {
            error(errors, path, keyword, minimum ? "number is below the minimum" : "number exceeds the maximum");
        }
    }

    private void compareSize(
            Object boundary,
            int size,
            boolean minimum,
            String path,
            String keyword,
            List<ToolSchemaValidationError> errors) {
        if (!(boundary instanceof Number number)) return;
        int limit = number.intValue();
        if ((minimum && size < limit) || (!minimum && size > limit)) {
            error(errors, path, keyword, minimum ? "value is smaller than the minimum" : "value exceeds the maximum");
        }
    }

    @SuppressWarnings("unchecked")
    private static Object resolveLocalReference(Map<String, Object> root, String reference) {
        if ("#".equals(reference)) return root;
        if (!reference.startsWith("#/")) return null;
        Object current = root;
        for (String token : reference.substring(2).split("/")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = ((Map<String, Object>) map).get(token.replace("~1", "/").replace("~0", "~"));
            if (current == null) return null;
        }
        return current;
    }

    private void error(List<ToolSchemaValidationError> errors, String path, String keyword, String message) {
        if (errors.size() < maxErrors) {
            errors.add(new ToolSchemaValidationError(path, keyword, message));
        }
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private ValidationBudget budget() {
        ValidationBudget budget = activeBudget.get();
        if (budget == null) throw new IllegalStateException("schema validation budget is unavailable");
        return budget;
    }

    private static final class ValidationBudget {
        private final long deadline;
        private int remainingNodes;

        private ValidationBudget(int maxNodes, long maxNanos) {
            remainingNodes = maxNodes;
            long now = System.nanoTime();
            deadline = maxNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + maxNanos;
        }

        private void consume() {
            if (--remainingNodes < 0) throw new ValidationLimitReached();
            check();
        }

        private void check() {
            if (System.nanoTime() > deadline) throw new ValidationLimitReached();
        }
    }

    private static final class ValidationLimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
