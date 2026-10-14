package io.haifa.agent.tool.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The declared schema document must already hold the boxed numeric types a JSON round trip produces,
 * so a persisted Tool definition stays equal to the declared one and a resume can re-validate its
 * binding against the frozen catalog.
 */
class ToolSchemaNumberCanonicalizationTest {

    @Test
    void widensIntegralValuesToTheJsonRoundTripType() {
        var schema = new ToolSchema(
                "id",
                "1",
                Map.<String, Object>of(
                        "maximum", 600_000L, "minimum", (short) 1000, "big", 3_000_000_000L, "ratio", 1.5d));

        assertEquals(Integer.valueOf(600_000), schema.document().get("maximum"));
        assertEquals(Integer.valueOf(1000), schema.document().get("minimum"));
        assertEquals(Long.valueOf(3_000_000_000L), schema.document().get("big"));
        assertEquals(Double.valueOf(1.5d), schema.document().get("ratio"));
    }

    @Test
    void keepsOutOfRangeIntegersAndFractionalNumbers() {
        var schema = new ToolSchema(
                "id",
                "1",
                Map.<String, Object>of(
                        "huge",
                        new BigInteger("9".repeat(40)),
                        "long",
                        BigInteger.valueOf(3_000_000_000L),
                        "float",
                        2.5f));

        assertInstanceOf(BigInteger.class, schema.document().get("huge"));
        assertEquals(Long.valueOf(3_000_000_000L), schema.document().get("long"));
        assertEquals(Double.valueOf(2.5d), schema.document().get("float"));
    }
}
