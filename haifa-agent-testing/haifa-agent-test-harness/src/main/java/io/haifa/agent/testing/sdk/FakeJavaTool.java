package io.haifa.agent.testing.sdk;

import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Deterministic Java Tool double with digest-only invocation evidence and safe failure injection. */
public final class FakeJavaTool<I extends Record, O extends Record> implements JavaTool<I, O> {
    private final JavaToolSpec<I, O> spec;
    private final O output;
    private final RuntimeException failure;
    private final List<String> inputDigests = new ArrayList<>();

    private FakeJavaTool(JavaToolSpec<I, O> spec, O output, RuntimeException failure) {
        this.spec = Objects.requireNonNull(spec, "spec must not be null");
        this.output = output;
        this.failure = failure;
    }

    public static <I extends Record, O extends Record> FakeJavaTool<I, O> responding(
            JavaToolSpec<I, O> spec, O output) {
        return new FakeJavaTool<>(spec, Objects.requireNonNull(output, "output must not be null"), null);
    }

    public static <I extends Record, O extends Record> FakeJavaTool<I, O> failing(
            JavaToolSpec<I, O> spec, RuntimeException failure) {
        return new FakeJavaTool<>(spec, null, Objects.requireNonNull(failure, "failure must not be null"));
    }

    @Override
    public JavaToolSpec<I, O> spec() {
        return spec;
    }

    @Override
    public synchronized O invoke(I input, JavaToolContext context) {
        Objects.requireNonNull(context, "context must not be null");
        inputDigests.add(
                digest(Objects.requireNonNull(input, "input must not be null").toString()));
        if (failure != null) throw failure;
        return output;
    }

    public synchronized List<String> inputDigests() {
        return List.copyOf(inputDigests);
    }

    private static String digest(String value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
