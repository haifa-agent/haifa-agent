package io.haifa.agent.sdk.internal;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Internal bounded schema and value mapper for Java record Tools. */
public final class JavaRecordSupport {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_COMPONENTS = 128;
    private static final int MAX_ARRAY_ITEMS = 1_000;
    private static final int MAX_STRING_CHARS = 65_536;
    private static final Set<Class<?>> STRING_TYPES =
            Set.of(UUID.class, URI.class, Instant.class, LocalDate.class, LocalDateTime.class, OffsetDateTime.class);

    private JavaRecordSupport() {}

    public static Map<String, Object> schema(Class<? extends Record> recordType) {
        requirePublicRecord(recordType);
        return objectSchema(recordType, new LinkedHashSet<>(), 0);
    }

    public static <T extends Record> T decode(Class<T> recordType, Map<String, Object> values) {
        requirePublicRecord(recordType);
        Objects.requireNonNull(values, "values must not be null");
        return recordType.cast(decodeRecord(recordType, values, 0));
    }

    public static Map<String, Object> encode(Record value) {
        Objects.requireNonNull(value, "value must not be null");
        requirePublicRecord(value.getClass().asSubclass(Record.class));
        return encodeRecord(value, 0);
    }

    private static Map<String, Object> objectSchema(Class<?> recordType, Set<Type> active, int depth) {
        checkDepth(depth);
        requirePublicRecord(recordType.asSubclass(Record.class));
        if (!active.add(recordType)) {
            throw new IllegalArgumentException(
                    "recursive Java record types are not supported: " + recordType.getName());
        }
        try {
            RecordComponent[] components = recordType.getRecordComponents();
            if (components.length > MAX_COMPONENTS) {
                throw new IllegalArgumentException("Java record exceeds " + MAX_COMPONENTS + " components");
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (RecordComponent component : components) {
                Type type = component.getGenericType();
                boolean optional = isOptional(type);
                properties.put(
                        component.getName(), schemaFor(optional ? typeArgument(type, 0) : type, active, depth + 1));
                if (!optional) required.add(component.getName());
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            schema.put("properties", Map.copyOf(properties));
            if (!required.isEmpty()) schema.put("required", List.copyOf(required));
            return Map.copyOf(schema);
        } finally {
            active.remove(recordType);
        }
    }

    private static Object schemaFor(Type type, Set<Type> active, int depth) {
        checkDepth(depth);
        if (type instanceof Class<?> raw) {
            if (raw == char.class || raw == Character.class) {
                return Map.of("type", "string", "minLength", 1, "maxLength", 1);
            }
            if (raw == String.class || STRING_TYPES.contains(raw)) {
                return Map.of("type", "string", "maxLength", MAX_STRING_CHARS);
            }
            if (raw == boolean.class || raw == Boolean.class) return Map.of("type", "boolean");
            if (isInteger(raw)) return Map.of("type", "integer");
            if (isNumber(raw)) return Map.of("type", "number");
            if (raw.isEnum()) {
                return Map.of(
                        "type",
                        "string",
                        "enum",
                        java.util.Arrays.stream(raw.getEnumConstants())
                                .map(value -> ((Enum<?>) value).name())
                                .toList());
            }
            if (raw.isRecord()) return objectSchema(raw, active, depth);
            if (raw.isArray()) {
                return Map.of(
                        "type",
                        "array",
                        "maxItems",
                        MAX_ARRAY_ITEMS,
                        "items",
                        schemaFor(raw.componentType(), active, depth + 1));
            }
            throw unsupported(type);
        }
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = rawClass(parameterized.getRawType());
            if (raw == Optional.class) {
                throw new IllegalArgumentException("Optional is only supported as a direct record component");
            }
            if (raw == List.class || raw == Set.class || raw == Collection.class) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "array");
                schema.put("maxItems", MAX_ARRAY_ITEMS);
                schema.put("items", schemaFor(typeArgument(parameterized, 0), active, depth + 1));
                if (raw == Set.class) schema.put("uniqueItems", true);
                return Map.copyOf(schema);
            }
            if (raw == Map.class) {
                Type key = typeArgument(parameterized, 0);
                if (key != String.class) throw new IllegalArgumentException("JavaTool Map keys must be String");
                return Map.of(
                        "type",
                        "object",
                        "maxProperties",
                        MAX_ARRAY_ITEMS,
                        "additionalProperties",
                        schemaFor(typeArgument(parameterized, 1), active, depth + 1));
            }
            throw unsupported(type);
        }
        if (type instanceof GenericArrayType array) {
            return Map.of(
                    "type",
                    "array",
                    "maxItems",
                    MAX_ARRAY_ITEMS,
                    "items",
                    schemaFor(array.getGenericComponentType(), active, depth + 1));
        }
        if (type instanceof WildcardType) {
            throw new IllegalArgumentException("JavaTool wildcard types are not supported: " + type.getTypeName());
        }
        throw unsupported(type);
    }

    private static Object decodeRecord(Class<?> recordType, Map<String, Object> values, int depth) {
        checkDepth(depth);
        requirePublicRecord(recordType.asSubclass(Record.class));
        RecordComponent[] components = recordType.getRecordComponents();
        if (components.length > MAX_COMPONENTS) {
            throw new IllegalArgumentException("Java record exceeds " + MAX_COMPONENTS + " components");
        }
        Set<String> componentNames = java.util.Arrays.stream(components)
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        values.keySet().stream()
                .filter(name -> !componentNames.contains(name))
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalArgumentException("unknown record component " + name);
                });
        Object[] arguments = new Object[components.length];
        Class<?>[] parameterTypes = new Class<?>[components.length];
        for (int index = 0; index < components.length; index++) {
            RecordComponent component = components[index];
            parameterTypes[index] = component.getType();
            Type genericType = component.getGenericType();
            if (isOptional(genericType)) {
                Object raw = values.get(component.getName());
                arguments[index] = raw == null
                        ? Optional.empty()
                        : Optional.of(decodeValue(typeArgument(genericType, 0), raw, depth + 1));
            } else {
                if (!values.containsKey(component.getName())) {
                    throw new IllegalArgumentException("missing record component " + component.getName());
                }
                arguments[index] = decodeValue(genericType, values.get(component.getName()), depth + 1);
            }
        }
        try {
            Constructor<?> constructor = recordType.getConstructor(parameterTypes);
            return constructor.newInstance(arguments);
        } catch (NoSuchMethodException exception) {
            throw new IllegalArgumentException(
                    "JavaTool records and their canonical constructors must be public", exception);
        } catch (InstantiationException | IllegalAccessException exception) {
            throw new IllegalArgumentException("cannot construct JavaTool record " + recordType.getName(), exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalArgumentException("JavaTool record constructor failed", cause);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object decodeValue(Type type, Object value, int depth) {
        checkDepth(depth);
        if (value == null) throw new IllegalArgumentException("JavaTool values must not be null");
        if (type instanceof Class<?> raw) {
            if (raw == String.class) return boundedString(value, type);
            if (raw == char.class || raw == Character.class) {
                String text = boundedString(value, type);
                if (text.length() != 1)
                    throw new IllegalArgumentException("character value must contain one character");
                return text.charAt(0);
            }
            if (raw == boolean.class || raw == Boolean.class) return requireType(value, Boolean.class, type);
            if (isInteger(raw)) return convertInteger(requireType(value, Number.class, type), raw);
            if (isNumber(raw)) return convertNumber(requireType(value, Number.class, type), raw);
            if (raw.isEnum()) return Enum.valueOf((Class<? extends Enum>) raw, boundedString(value, type));
            if (raw == UUID.class) return UUID.fromString(boundedString(value, type));
            if (raw == URI.class) return URI.create(boundedString(value, type));
            if (raw == Instant.class) return Instant.parse(boundedString(value, type));
            if (raw == LocalDate.class) return LocalDate.parse(boundedString(value, type));
            if (raw == LocalDateTime.class) return LocalDateTime.parse(boundedString(value, type));
            if (raw == OffsetDateTime.class) return OffsetDateTime.parse(boundedString(value, type));
            if (raw.isRecord()) return decodeRecord(raw, stringMap(value, type), depth + 1);
            if (raw.isArray()) {
                List<?> list = requireType(value, List.class, type);
                requireCollectionSize(list.size());
                Object array = Array.newInstance(raw.componentType(), list.size());
                for (int index = 0; index < list.size(); index++) {
                    Array.set(array, index, decodeValue(raw.componentType(), list.get(index), depth + 1));
                }
                return array;
            }
            throw unsupported(type);
        }
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = rawClass(parameterized.getRawType());
            if (raw == Optional.class)
                return Optional.of(decodeValue(typeArgument(parameterized, 0), value, depth + 1));
            if (raw == List.class || raw == Collection.class) {
                List<?> source = requireType(value, List.class, type);
                requireCollectionSize(source.size());
                return source.stream()
                        .map(item -> decodeValue(typeArgument(parameterized, 0), item, depth + 1))
                        .toList();
            }
            if (raw == Set.class) {
                List<?> source = requireType(value, List.class, type);
                requireCollectionSize(source.size());
                LinkedHashSet<Object> result = new LinkedHashSet<>();
                source.forEach(item -> result.add(decodeValue(typeArgument(parameterized, 0), item, depth + 1)));
                if (result.size() != source.size()) {
                    throw new IllegalArgumentException("JavaTool Set input contains duplicate values");
                }
                return Set.copyOf(result);
            }
            if (raw == Map.class) {
                Type key = typeArgument(parameterized, 0);
                if (key != String.class) throw new IllegalArgumentException("JavaTool Map keys must be String");
                Map<String, Object> source = stringMap(value, type);
                requireCollectionSize(source.size());
                Map<String, Object> result = new LinkedHashMap<>();
                source.forEach(
                        (name, item) -> result.put(name, decodeValue(typeArgument(parameterized, 1), item, depth + 1)));
                return Map.copyOf(result);
            }
            throw unsupported(type);
        }
        throw unsupported(type);
    }

    private static Map<String, Object> encodeRecord(Record value, int depth) {
        checkDepth(depth);
        Map<String, Object> result = new LinkedHashMap<>();
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            Object field;
            try {
                field = component.getAccessor().invoke(value);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalArgumentException(
                        "cannot read JavaTool record component " + component.getName(), exception);
            }
            if (field instanceof Optional<?> optional) {
                optional.ifPresent(item -> result.put(component.getName(), encodeValue(item, depth + 1)));
            } else {
                if (field == null) throw new IllegalArgumentException("JavaTool output components must not be null");
                result.put(component.getName(), encodeValue(field, depth + 1));
            }
        }
        return Map.copyOf(result);
    }

    private static Object encodeValue(Object value, int depth) {
        checkDepth(depth);
        if (value instanceof Double number && !Double.isFinite(number)) {
            throw new IllegalArgumentException("JavaTool numeric values must be finite");
        }
        if (value instanceof Float number && !Float.isFinite(number)) {
            throw new IllegalArgumentException("JavaTool numeric values must be finite");
        }
        if (value instanceof String text) {
            if (text.length() > MAX_STRING_CHARS) {
                throw new IllegalArgumentException("JavaTool string value is too long");
            }
            return text;
        }
        if (value instanceof Boolean || value instanceof Number) return value;
        if (value instanceof Character character) return checkedEncodedString(character.toString());
        if (value instanceof Enum<?> enumeration) return checkedEncodedString(enumeration.name());
        if (value instanceof UUID
                || value instanceof URI
                || value instanceof Instant
                || value instanceof LocalDate
                || value instanceof LocalDateTime
                || value instanceof OffsetDateTime) return checkedEncodedString(value.toString());
        if (value instanceof Record record) return encodeRecord(record, depth + 1);
        if (value instanceof Optional<?>) {
            throw new IllegalArgumentException("Optional is only supported as a direct record component");
        }
        if (value instanceof Map<?, ?> map) {
            requireCollectionSize(map.size());
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text))
                    throw new IllegalArgumentException("JavaTool Map keys must be String");
                if (item == null) throw new IllegalArgumentException("JavaTool Map values must not be null");
                result.put(text, encodeValue(item, depth + 1));
            });
            return Map.copyOf(result);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(item -> {
                if (item == null) throw new IllegalArgumentException("JavaTool collection values must not be null");
                result.add(encodeValue(item, depth + 1));
            });
            if (result.size() > MAX_ARRAY_ITEMS) throw new IllegalArgumentException("JavaTool collection is too large");
            return List.copyOf(result);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            if (length > MAX_ARRAY_ITEMS) throw new IllegalArgumentException("JavaTool array is too large");
            List<Object> result = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                result.add(encodeValue(Array.get(value, index), depth + 1));
            }
            return List.copyOf(result);
        }
        throw new IllegalArgumentException(
                "unsupported JavaTool output type: " + value.getClass().getName());
    }

    private static boolean isOptional(Type type) {
        return type instanceof ParameterizedType parameterized && parameterized.getRawType() == Optional.class;
    }

    private static Type typeArgument(Type type, int index) {
        if (!(type instanceof ParameterizedType parameterized)) {
            throw new IllegalArgumentException("type must declare generic arguments: " + type.getTypeName());
        }
        return typeArgument(parameterized, index);
    }

    private static Type typeArgument(ParameterizedType type, int index) {
        Type[] arguments = type.getActualTypeArguments();
        if (index >= arguments.length) throw new IllegalArgumentException("missing generic type argument");
        return arguments[index];
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> raw) return raw;
        throw unsupported(type);
    }

    private static boolean isInteger(Class<?> type) {
        return type == byte.class
                || type == Byte.class
                || type == short.class
                || type == Short.class
                || type == int.class
                || type == Integer.class
                || type == long.class
                || type == Long.class
                || type == BigInteger.class;
    }

    private static boolean isNumber(Class<?> type) {
        return type == float.class
                || type == Float.class
                || type == double.class
                || type == Double.class
                || type == BigDecimal.class;
    }

    private static Object convertInteger(Number value, Class<?> target) {
        BigInteger integer;
        try {
            integer = value instanceof BigInteger exact ? exact : new BigDecimal(value.toString()).toBigIntegerExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("integer value has a fractional component", exception);
        }
        if (target == BigInteger.class) return integer;
        try {
            if (target == byte.class || target == Byte.class) return integer.byteValueExact();
            if (target == short.class || target == Short.class) return integer.shortValueExact();
            if (target == int.class || target == Integer.class) return integer.intValueExact();
            return integer.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("integer value is outside the Java target range", exception);
        }
    }

    private static Object convertNumber(Number value, Class<?> target) {
        BigDecimal decimal = value instanceof BigDecimal exact ? exact : new BigDecimal(value.toString());
        if (target == BigDecimal.class) return decimal;
        if (target == float.class || target == Float.class) return decimal.floatValue();
        return decimal.doubleValue();
    }

    private static <T> T requireType(Object value, Class<T> expected, Type declared) {
        if (!expected.isInstance(value)) {
            throw new IllegalArgumentException(
                    "value for " + declared.getTypeName() + " must be " + expected.getSimpleName());
        }
        return expected.cast(value);
    }

    private static String boundedString(Object value, Type declared) {
        String text = requireType(value, String.class, declared);
        return checkedEncodedString(text);
    }

    private static String checkedEncodedString(String text) {
        if (text.length() > MAX_STRING_CHARS) {
            throw new IllegalArgumentException("JavaTool string value is too long");
        }
        return text;
    }

    private static void requireCollectionSize(int size) {
        if (size > MAX_ARRAY_ITEMS) {
            throw new IllegalArgumentException("JavaTool collection is too large");
        }
    }

    private static Map<String, Object> stringMap(Object value, Type declared) {
        Map<?, ?> map = requireType(value, Map.class, declared);
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (!(key instanceof String text))
                throw new IllegalArgumentException("JavaTool object keys must be String");
            result.put(text, item);
        });
        return result;
    }

    private static void requirePublicRecord(Class<? extends Record> type) {
        Objects.requireNonNull(type, "record type must not be null");
        if (!type.isRecord() || !isPubliclyAccessible(type)) {
            throw new IllegalArgumentException("JavaTool types must be public Java records: " + type.getName());
        }
    }

    private static boolean isPubliclyAccessible(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!java.lang.reflect.Modifier.isPublic(current.getModifiers())) return false;
        }
        return true;
    }

    private static void checkDepth(int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JavaTool record nesting exceeds " + MAX_DEPTH);
    }

    private static IllegalArgumentException unsupported(Type type) {
        return new IllegalArgumentException("unsupported JavaTool record type: " + type.getTypeName());
    }
}
