package io.haifa.agent.sdk.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.sdk.internal.JavaRecordSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class JavaRecordSchemaGeneratorTest {
    @Test
    @SuppressWarnings("unchecked")
    void generatesSchemaAndRoundTripsSupportedRecordValues() {
        var generator = new JavaRecordSchemaGenerator();
        var schema = generator.generate("weather.request", "1.0.0", WeatherRequest.class);

        assertThat(schema.document()).containsEntry("type", "object");
        List<Object> required = (List<Object>) schema.document().get("required");
        assertThat(required).containsExactly("city", "days", "alerts");
        Map<String, Object> properties = (Map<String, Object>) schema.document().get("properties");
        assertThat(properties).containsKeys("city", "days", "unit", "alerts");
        Map<String, Object> unit = (Map<String, Object>) properties.get("unit");
        assertThat(unit.get("enum")).isEqualTo(List.of("CELSIUS", "FAHRENHEIT"));

        Map<String, Object> values = Map.of(
                "city",
                "Shanghai",
                "days",
                2,
                "unit",
                "CELSIUS",
                "alerts",
                List.of(Map.of("code", "RAIN", "at", "2026-08-05T01:02:03Z")));
        WeatherRequest request = JavaRecordSupport.decode(WeatherRequest.class, values);

        assertThat(request)
                .isEqualTo(new WeatherRequest(
                        "Shanghai",
                        2,
                        Optional.of(Unit.CELSIUS),
                        List.of(new Alert("RAIN", Instant.parse("2026-08-05T01:02:03Z")))));
        assertThat(JavaRecordSupport.encode(request)).isEqualTo(values);
    }

    @Test
    void rejectsRecursiveRecordsAndUnsupportedNestedOptional() {
        var generator = new JavaRecordSchemaGenerator();

        assertThatThrownBy(() -> generator.generate("node", "1.0.0", Node.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recursive");
        assertThatThrownBy(() -> generator.generate("nested", "1.0.0", NestedOptional.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("direct record component");
    }

    @Test
    void rejectsUnknownNullOversizedAndNonFiniteValuesWithoutEchoingInput() {
        String sensitive = "sensitive-value";

        assertThatThrownBy(() -> JavaRecordSupport.decode(
                        WeatherRequest.class,
                        Map.of("city", "Shanghai", "days", 2, "alerts", List.of(), "unexpected", sensitive)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown record component unexpected")
                .hasMessageNotContaining(sensitive);
        assertThatThrownBy(() -> JavaRecordSupport.decode(TextValue.class, Map.of("value", "x".repeat(65_537))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JavaTool string value is too long");
        assertThatThrownBy(() -> JavaRecordSupport.decode(
                        Values.class, Map.of("values", java.util.Collections.nCopies(1_001, "x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JavaTool collection is too large");
        assertThatThrownBy(() -> JavaRecordSupport.encode(new DecimalValue(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JavaTool numeric values must be finite");
        assertThatThrownBy(() -> JavaRecordSupport.encode(new NullableValue(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JavaTool output components must not be null");
    }

    public enum Unit {
        CELSIUS,
        FAHRENHEIT;

        @Override
        public String toString() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record Alert(String code, Instant at) {}

    public record WeatherRequest(String city, int days, Optional<Unit> unit, List<Alert> alerts) {}

    public record Node(String value, Optional<Node> next) {}

    public record NestedOptional(List<Optional<String>> values) {}

    public record TextValue(String value) {}

    public record Values(List<String> values) {}

    public record DecimalValue(double value) {}

    public record NullableValue(String value) {}

    public record TranslationResult(Map<String, List<String>> translations) {}

    @Test
    @SuppressWarnings("unchecked")
    void mapOfStringListStringSchemaAndRoundTrip() {
        var schema = JavaRecordSupport.schema(TranslationResult.class);
        assertThat(schema).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> translations = (Map<String, Object>) properties.get("translations");
        assertThat(translations).containsEntry("type", "object");
        Map<String, Object> additionalProperties = (Map<String, Object>) translations.get("additionalProperties");
        assertThat(additionalProperties).containsEntry("type", "array");

        Map<String, Object> values = Map.of(
                "translations",
                Map.of(
                        "Japanese", List.of("こんにちは", "さようなら"),
                        "French", List.of("Bonjour", "Au revoir")));
        TranslationResult result = JavaRecordSupport.decode(TranslationResult.class, values);
        assertThat(result.translations()).containsEntry("Japanese", List.of("こんにちは", "さようなら"));
        assertThat(result.translations()).containsEntry("French", List.of("Bonjour", "Au revoir"));
        assertThat(JavaRecordSupport.encode(result)).isEqualTo(values);
    }
}
