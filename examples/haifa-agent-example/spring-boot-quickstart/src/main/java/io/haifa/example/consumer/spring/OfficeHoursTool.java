package io.haifa.example.consumer.spring;

import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import org.springframework.stereotype.Component;

/** Application-owned typed Tool discovered automatically as a Spring Bean. */
@Component
public final class OfficeHoursTool implements JavaTool<OfficeHoursTool.Request, OfficeHoursTool.Response> {
    public record Request(String office) {}

    public record Response(String hours) {}

    private static final JavaToolSpec<Request, Response> SPEC = JavaToolSpec.builder(
                    "office.hours", Request.class, Response.class)
            .alias("office_hours")
            .title("Office hours")
            .description("Return deterministic opening hours for an office")
            .pure()
            .build();

    @Override
    public JavaToolSpec<Request, Response> spec() {
        return SPEC;
    }

    @Override
    public Response invoke(Request input, JavaToolContext context) {
        return new Response(input.office() + " is open from 09:00 to 17:00");
    }

    @Override
    public String summarize(Response output) {
        return output.hours();
    }
}
