package io.haifa.agent.execution.core.tool;

import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.policy.api.PolicyDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.UnaryOperator;

public record ExecutionToolConfiguration(
        ExecutionEnvironmentRef environmentRef,
        SandboxProfileRef sandboxProfileRef,
        Duration defaultTimeout,
        Duration maximumTimeout,
        int maximumOutputBytes,
        int maximumOutputLines,
        int maximumProcesses,
        boolean workingDirectoryAllowed,
        ScriptRuntimeResolver runtimes,
        ExecutionOutputObserver outputObserver,
        UnaryOperator<String> outputSanitizer,
        ExecutionScratchSpaceSpec scratchSpace) {
    public ExecutionToolConfiguration(
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef sandboxProfileRef,
            Duration defaultTimeout,
            Duration maximumTimeout,
            int maximumOutputBytes,
            int maximumOutputLines,
            int maximumProcesses,
            boolean workingDirectoryAllowed,
            ScriptRuntimeResolver runtimes,
            ExecutionOutputObserver outputObserver,
            UnaryOperator<String> outputSanitizer) {
        this(
                environmentRef,
                sandboxProfileRef,
                defaultTimeout,
                maximumTimeout,
                maximumOutputBytes,
                maximumOutputLines,
                maximumProcesses,
                workingDirectoryAllowed,
                runtimes,
                outputObserver,
                outputSanitizer,
                ExecutionScratchSpaceSpec.genericRequired());
    }

    public ExecutionToolConfiguration {
        Objects.requireNonNull(environmentRef, "environmentRef must not be null");
        Objects.requireNonNull(sandboxProfileRef, "sandboxProfileRef must not be null");
        positive(defaultTimeout, "defaultTimeout");
        positive(maximumTimeout, "maximumTimeout");
        if (defaultTimeout.compareTo(maximumTimeout) > 0 || maximumTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("execution tool timeout must be within 30 seconds");
        }
        if (maximumOutputBytes < 1024 || maximumOutputBytes > 1024 * 1024) {
            throw new IllegalArgumentException("maximumOutputBytes is out of range");
        }
        if (maximumOutputLines < 1 || maximumOutputLines > 10_000) {
            throw new IllegalArgumentException("maximumOutputLines is out of range");
        }
        if (maximumProcesses < 1 || maximumProcesses > 64) {
            throw new IllegalArgumentException("maximumProcesses is out of range");
        }
        Objects.requireNonNull(runtimes, "runtimes must not be null");
        Objects.requireNonNull(outputObserver, "outputObserver must not be null");
        Objects.requireNonNull(outputSanitizer, "outputSanitizer must not be null");
        Objects.requireNonNull(scratchSpace, "scratchSpace must not be null");
    }

    public String identityDigest() {
        var fields = new ArrayList<String>();
        fields.add("execution-tool-configuration-v2");
        environmentRef.leaseRefs().stream().sorted().forEach(fields::add);
        fields.add(sandboxProfileRef.value());
        fields.add(sandboxProfileRef.version());
        fields.add(Long.toString(defaultTimeout.toMillis()));
        fields.add(Long.toString(maximumTimeout.toMillis()));
        fields.add(Integer.toString(maximumOutputBytes));
        fields.add(Integer.toString(maximumOutputLines));
        fields.add(Integer.toString(maximumProcesses));
        fields.add(Boolean.toString(workingDirectoryAllowed));
        fields.add(runtimes.operatingSystem().name());
        runtimes.languages().stream().sorted().forEach(fields::add);
        runtimes.executableNames().stream().sorted().forEach(fields::add);
        fields.add(scratchSpace.canonicalDigest());
        return PolicyDigest.sha256Fields(fields);
    }

    private static void positive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(field + " must be positive");
    }
}
