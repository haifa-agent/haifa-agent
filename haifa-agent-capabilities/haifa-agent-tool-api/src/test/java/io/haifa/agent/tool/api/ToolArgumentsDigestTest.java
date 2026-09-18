package io.haifa.agent.tool.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.haifa.agent.core.tool.ToolArguments;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolArgumentsDigestTest {
    @Test
    void canonicalizesMapOrderButBindsEveryArgumentField() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("mode", "SCRIPT");
        first.put("content", "Write-Output $args");
        first.put("args", List.of("one", "two"));
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("args", List.of("one", "two"));
        reordered.put("content", "Write-Output $args");
        reordered.put("mode", "SCRIPT");

        String digest = ToolArgumentsDigest.sha256(new ToolArguments("execution", "2.0.0", first));

        assertEquals(64, digest.length());
        assertEquals(digest, ToolArgumentsDigest.sha256(new ToolArguments("execution", "2.0.0", reordered)));
        assertNotEquals(
                digest,
                ToolArgumentsDigest.sha256(
                        new ToolArguments("execution", "2.0.0", Map.of("mode", "SCRIPT", "content", "changed"))));
    }
}
