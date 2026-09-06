package io.haifa.agent.execution.core.tool;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Deployment-neutral runtime allowlist. Host discovery is supplied by an execution-host factory. */
public final class ScriptRuntimeResolver {
    private final ExecutionOperatingSystem operatingSystem;
    private final Map<String, ScriptRuntimeAdapter> adapters;

    public ScriptRuntimeResolver(
            ExecutionOperatingSystem operatingSystem, List<? extends ScriptRuntimeAdapter> adapters) {
        this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem must not be null");
        Map<String, ScriptRuntimeAdapter> indexed = new LinkedHashMap<>();
        for (ScriptRuntimeAdapter adapter : List.copyOf(adapters)) {
            String language = normalize(adapter.language());
            if (indexed.putIfAbsent(language, adapter) != null) {
                throw new IllegalArgumentException("duplicate script runtime: " + language);
            }
        }
        this.adapters = Map.copyOf(indexed);
    }

    public ScriptRuntimeAdapter resolve(String language) {
        String normalized = normalize(language);
        ScriptRuntimeAdapter adapter = adapters.get(normalized);
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "script language " + normalized + " is not configured for " + operatingSystem.name());
        }
        return adapter;
    }

    public String defaultLanguage() {
        return operatingSystem == ExecutionOperatingSystem.WINDOWS ? "powershell" : "bash";
    }

    public java.util.Set<String> languages() {
        return adapters.keySet();
    }

    public java.util.Set<String> executableNames() {
        return adapters.values().stream()
                .map(ScriptRuntimeAdapter::executable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public ExecutionOperatingSystem operatingSystem() {
        return operatingSystem;
    }

    public static ScriptRuntimeAdapter bash(String executable) {
        return new StandardInputRuntimeAdapter("bash", executable, List.of("--noprofile", "--norc", "-s", "--"));
    }

    public static ScriptRuntimeAdapter python(String executable) {
        return new StandardInputRuntimeAdapter("python", executable, List.of("-X", "utf8", "-I", "-"));
    }

    public static ScriptRuntimeAdapter powerShell(String executable) {
        return new StandardInputRuntimeAdapter(
                "powershell", executable, List.of("-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "-"));
    }

    private static String normalize(String value) {
        String normalized = Objects.requireNonNull(value, "language must not be null")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException("language must not be blank");
        return normalized;
    }

    private record StandardInputRuntimeAdapter(String language, String executablePath, List<String> invocationArguments)
            implements ScriptRuntimeAdapter {
        private StandardInputRuntimeAdapter {
            language = normalize(language);
            executablePath = Objects.requireNonNull(executablePath, "executablePath must not be null")
                    .trim();
            if (executablePath.isEmpty()) throw new IllegalArgumentException("executablePath must not be blank");
            invocationArguments = List.copyOf(invocationArguments);
        }

        @Override
        public String executable() {
            return executablePath;
        }

        @Override
        public PreparedScript prepare(String content, List<String> arguments) {
            Objects.requireNonNull(content, "content must not be null");
            List<String> safeArguments = List.copyOf(arguments);
            List<String> argv = new ArrayList<>();
            argv.add(executablePath);
            argv.addAll(invocationArguments);
            String source = content;
            if (language.equals("powershell")) {
                String encodedArguments = safeArguments.stream()
                        .map(ScriptRuntimeResolver::quotePowerShell)
                        .collect(java.util.stream.Collectors.joining(" "));
                String encodedSource = java.util.Base64.getEncoder()
                        .encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                source = "$__haifaUtf8 = [Text.UTF8Encoding]::new($false)\n"
                        + "[Console]::InputEncoding = $__haifaUtf8\n"
                        + "[Console]::OutputEncoding = $__haifaUtf8\n"
                        + "$OutputEncoding = $__haifaUtf8\n"
                        + "$__haifaScriptSource = [Text.Encoding]::UTF8.GetString("
                        + "[Convert]::FromBase64String('" + encodedSource + "'))\n"
                        + "& ([ScriptBlock]::Create($__haifaScriptSource))"
                        + (encodedArguments.isEmpty() ? "" : " " + encodedArguments);
            } else {
                argv.addAll(safeArguments);
            }
            if (!source.endsWith("\n")) source += "\n";
            return new PreparedScript(ExecutionCommand.direct(argv), ExecutionInput.utf8(source));
        }
    }

    private static String quotePowerShell(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
