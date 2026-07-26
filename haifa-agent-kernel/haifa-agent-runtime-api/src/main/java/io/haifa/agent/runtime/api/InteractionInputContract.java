package io.haifa.agent.runtime.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded input contract; it is not an executable JSON Schema or external URL. */
public record InteractionInputContract(
        InteractionInputType type,
        int maximumCharacters,
        int minimumSelections,
        int maximumSelections,
        int maximumParts,
        int maximumBytes,
        List<InteractionOption> options,
        Optional<String> schemaRef) {

    public static final InteractionInputContract NONE =
            new InteractionInputContract(InteractionInputType.NONE, 0, 0, 0, 0, 0, List.of(), Optional.empty());

    public InteractionInputContract {
        type = Objects.requireNonNull(type, "type must not be null");
        options = List.copyOf(Objects.requireNonNull(options, "options must not be null"));
        schemaRef = Objects.requireNonNull(schemaRef, "schemaRef must not be null")
                .map(value -> InteractionOption.requireText(value, "schemaRef", 256));
        if (maximumCharacters < 0
                || minimumSelections < 0
                || maximumSelections < 0
                || maximumParts < 0
                || maximumBytes < 0) {
            throw new IllegalArgumentException("input limits must not be negative");
        }
        if (maximumCharacters > 65_536 || maximumSelections > 100 || maximumParts > 100 || maximumBytes > 1_048_576) {
            throw new IllegalArgumentException("input limits exceed the public safety budget");
        }
        if (minimumSelections > maximumSelections) {
            throw new IllegalArgumentException("minimumSelections must not exceed maximumSelections");
        }
        if (options.size() > 100
                || new HashSet<>(options.stream().map(InteractionOption::id).toList()).size() != options.size()) {
            throw new IllegalArgumentException("options must be bounded and have unique ids");
        }
        if (!type.known()) {
            requireNoInput(maximumCharacters, maximumSelections, maximumParts, maximumBytes, options, schemaRef);
        } else if (type.equals(InteractionInputType.NONE)) {
            requireNoInput(maximumCharacters, maximumSelections, maximumParts, maximumBytes, options, schemaRef);
        } else if (type.equals(InteractionInputType.TEXT)) {
            if (maximumCharacters < 1) throw new IllegalArgumentException("text requires maximumCharacters");
            requireNoOptionsOrSchema(options, schemaRef);
        } else if (type.equals(InteractionInputType.SINGLE_CHOICE)) {
            if (options.isEmpty() || minimumSelections > 1 || maximumSelections != 1) {
                throw new IllegalArgumentException("single-choice requires options and exactly one maximum selection");
            }
            requireNoSchema(schemaRef);
        } else if (type.equals(InteractionInputType.MULTI_CHOICE)) {
            if (options.isEmpty() || maximumSelections < 1 || maximumSelections > options.size()) {
                throw new IllegalArgumentException("multi-choice selection limits must fit the options");
            }
            requireNoSchema(schemaRef);
        } else if (type.equals(InteractionInputType.CONTENT_PARTS)) {
            if (maximumParts < 1 || maximumBytes < 1) {
                throw new IllegalArgumentException("content-parts requires part and byte limits");
            }
            requireNoOptionsOrSchema(options, schemaRef);
        } else if (type.equals(InteractionInputType.SCHEMA_REF)) {
            if (schemaRef.isEmpty() || maximumBytes < 1) {
                throw new IllegalArgumentException("schema-ref requires a registered ref and byte limit");
            }
            if (!options.isEmpty()) throw new IllegalArgumentException("schema-ref must not contain options");
        }
    }

    public static InteractionInputContract text(int maximumCharacters) {
        return new InteractionInputContract(
                InteractionInputType.TEXT, maximumCharacters, 0, 0, 0, 0, List.of(), Optional.empty());
    }

    public static InteractionInputContract contentParts(int maximumParts, int maximumBytes) {
        return new InteractionInputContract(
                InteractionInputType.CONTENT_PARTS, 0, 0, 0, maximumParts, maximumBytes, List.of(), Optional.empty());
    }

    private static void requireNoInput(
            int maximumCharacters,
            int maximumSelections,
            int maximumParts,
            int maximumBytes,
            List<InteractionOption> options,
            Optional<String> schemaRef) {
        if (maximumCharacters != 0
                || maximumSelections != 0
                || maximumParts != 0
                || maximumBytes != 0
                || !options.isEmpty()
                || schemaRef.isPresent()) {
            throw new IllegalArgumentException("none input must not declare input constraints");
        }
    }

    private static void requireNoOptionsOrSchema(List<InteractionOption> options, Optional<String> schemaRef) {
        if (!options.isEmpty() || schemaRef.isPresent()) {
            throw new IllegalArgumentException("input type must not declare options or schemaRef");
        }
    }

    private static void requireNoSchema(Optional<String> schemaRef) {
        if (schemaRef.isPresent()) throw new IllegalArgumentException("choice input must not declare schemaRef");
    }
}
