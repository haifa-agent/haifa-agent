package io.haifa.example.sdk.intermediate;

import io.haifa.agent.sdk.tool.JavaRecordSchemaGenerator;

/** Generates schemas for enum, Optional, nested record, collection, map, and date fields. */
public final class ComplexRecordSchemaExample {
    private ComplexRecordSchemaExample() {}

    public static void main(String[] arguments) {
        var generator = new JavaRecordSchemaGenerator();
        var input = generator.generate("trip-plan.input", "1.0", TripPlanTool.Request.class);
        var output = generator.generate("trip-plan.output", "1.0", TripPlanTool.Response.class);

        System.out.printf(
                "tool=%s inputProperties=%s outputProperties=%s%n",
                new TripPlanTool().spec().alias().value(),
                input.document().get("properties"),
                output.document().get("properties"));
    }
}
