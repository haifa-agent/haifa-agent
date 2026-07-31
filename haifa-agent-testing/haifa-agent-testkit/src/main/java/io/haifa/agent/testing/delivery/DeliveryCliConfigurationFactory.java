package io.haifa.agent.testing.delivery;

import java.nio.file.Path;
import java.util.Map;

/** Shared CLI configuration with only the execution fragment supplied by the host profile. */
final class DeliveryCliConfigurationFactory {
    private DeliveryCliConfigurationFactory() {}

    static String render(
            AutonomousDeliverySuiteManifest suite,
            DeliveryToolchainSet toolchains,
            DeliveryHostProfile profile,
            AutonomousDeliveryMatrixManifest.Combination matrixCombination) {
        Map<String, Path> roots = toolchains.pathRoots();
        return """
                models:
                  default: %s
                  providers:
                    - id: deepseek
                      displayName: DeepSeek
                      endpoint: https://api.deepseek.com
                      credentialRef: env://DEEPSEEK_API_KEY
                      models:
                        - id: %s
                          displayName: %s
                          providerModelId: %s
                tools:
                  enabled: [file.list, file.stat, file.read, file.search, file.create, file.write, file.delete, file.move, execution.run]
                skills:
                  allowed: [task-planning, result-verification]
                approval:
                  mode: auto
                execution:
                  provider: %s
                  network: %s
                  shell: %s
                  defaultTimeoutMillis: 120000
                  maxTimeoutMillis: 600000
                  maxOutputLines: 2000
                  maxOutputBytes: 102400
                  maxProcesses: 32
                  inheritEnvironment: [PATH, JAVA_HOME]
                  extraPathPolicies:
                    - { id: java-toolchain, path: %s, readOnly: true }
                    - { id: python-toolchain, path: %s, readOnly: true }
                    - { id: node-toolchain, path: %s, readOnly: true }
                    - { id: go-toolchain, path: %s, readOnly: true }
                    - { id: git-toolchain, path: %s, readOnly: true }
                runtime:
                  maxIterations: %d
                  maxToolCalls: %d
                  maxWallTimeMillis: %d
                persistence:
                  mode: SQLITE_WITH_JSONL
                  protectorRef: env://HAIFA_CONTINUATION_KEY
                  busyTimeoutMillis: 5000
                  maximumPayloadBytes: 1048576
                """
                .formatted(
                        matrixCombination.modelId(),
                        matrixCombination.modelId(),
                        matrixCombination.modelId(),
                        matrixCombination.modelId(),
                        profile.executionProvider(),
                        profile.networkPolicy(),
                        profile.shell(),
                        yamlPath(roots.get("java")),
                        yamlPath(roots.get("python")),
                        yamlPath(roots.get("node")),
                        yamlPath(roots.get("go")),
                        yamlPath(roots.get("git")),
                        suite.budget().maxIterations(),
                        suite.budget().maxToolCalls(),
                        suite.budget().maxWallTimeMillis());
    }

    private static String yamlPath(Path path) {
        return "'" + path.toString().replace("'", "''") + "'";
    }
}
