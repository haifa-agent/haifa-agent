package io.haifa.agent.testing.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HarnessLifecycleTest {
    @Test
    void executesTheSixStagesInOrder() throws Exception {
        List<String> stages = new ArrayList<>();
        HarnessLifecycle<String, String, String, String, String> lifecycle =
                new HarnessLifecycle<>(new HarnessLifecycle.Stages<>() {
                    public String resolve(TestRunRequest request) {
                        stages.add("resolve");
                        return "resolved";
                    }

                    public void preflight(TestRunRequest request, String resolved) {
                        stages.add("preflight");
                    }

                    public String provision(TestRunRequest request, String resolved) {
                        stages.add("provision");
                        return "prepared";
                    }

                    public String execute(TestRunRequest request, String resolved, String prepared) {
                        stages.add("execute");
                        return "executed";
                    }

                    public String grade(TestRunRequest request, String resolved, String executed) {
                        stages.add("grade");
                        return "graded";
                    }

                    public String finalizeRun(TestRunRequest request, String resolved, String executed, String graded) {
                        stages.add("finalize");
                        return "done";
                    }
                });

        String result = lifecycle.run(new TestRunRequest(
                Path.of("product"),
                Path.of("config"),
                Path.of("runs"),
                "suite",
                "profile",
                "platform",
                RunMode.DEV,
                null,
                null));

        assertEquals("done", result);
        assertEquals(List.of("resolve", "preflight", "provision", "execute", "grade", "finalize"), stages);
    }
}
