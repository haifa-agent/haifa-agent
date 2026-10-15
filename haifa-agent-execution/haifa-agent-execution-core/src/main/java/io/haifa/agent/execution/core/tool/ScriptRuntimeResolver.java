package io.haifa.agent.execution.core.tool;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deployment-neutral runtime allowlist. Host discovery is supplied by an execution-host factory.
 *
 * <p>A language is either executed through standard input ({@link #languages()}) or executes a
 * frozen package entry file ({@link #fileExecutionLanguages()}); a single adapter never claims both
 * modes, because the two argv shapes have different semantics.
 */
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

    /** Languages executable from standard input; this is the model-visible surface. */
    public java.util.Set<String> languages() {
        return adapters.values().stream()
                .filter(ScriptRuntimeAdapter::supportsStandardInput)
                .map(ScriptRuntimeAdapter::language)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Languages executed through a materialized package entry file; never model-visible. */
    public java.util.Set<String> fileExecutionLanguages() {
        return adapters.values().stream()
                .filter(ScriptRuntimeAdapter::supportsFileExecution)
                .map(ScriptRuntimeAdapter::language)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
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

    /** Trusted package runtime that executes one entry file: {@code bun <entryFile> <args...>}. */
    public static ScriptRuntimeAdapter bun(String executable) {
        return new FileExecutionRuntimeAdapter("bun", executable, List.of(), false);
    }

    /** Trusted package runtime for bash entry files: {@code bash <entryFile> <args...>}. */
    public static ScriptRuntimeAdapter bashFile(String executable) {
        return new FileExecutionRuntimeAdapter("bash", executable, List.of(), false);
    }

    /** Trusted package runtime for isolated python entry files: {@code python -I <entryFile> <args...>}. */
    public static ScriptRuntimeAdapter pythonFile(String executable) {
        return new FileExecutionRuntimeAdapter("python", executable, List.of("-I"), false);
    }

    /** Trusted package runtime for PowerShell entry files: {@code powershell -File <entryFile> <args...>}. */
    public static ScriptRuntimeAdapter powerShellFile(String executable) {
        return new FileExecutionRuntimeAdapter(
                "powershell", executable, List.of("-NoLogo", "-NoProfile", "-NonInteractive", "-File"), true);
    }

    /**
     * Builds one declared trusted package runtime. Deployment-neutral by design: only the product
     * may declare the executable and never a model, so the language label is validated but not
     * allowlisted here. This adapter executes a frozen package entry file and rejects standard-input
     * execution.
     */
    public static ScriptRuntimeAdapter trustedPackageRuntime(
            String language, String executable, List<String> invocationArguments) {
        return new FileExecutionRuntimeAdapter(language, executable, invocationArguments, false);
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

    /**
     * Executes a materialized frozen package entry file by path. The entry path is a Workspace-root
     * relative path produced by the trusted materializer; argv never carries an absolute path, a
     * drive-letter form or a {@code ..} segment.
     */
    private record FileExecutionRuntimeAdapter(
            String language, String executablePath, List<String> invocationArguments, boolean quotedPowerShellArguments)
            implements ScriptRuntimeAdapter {
        private static final java.util.regex.Pattern LANGUAGE = java.util.regex.Pattern.compile("[a-z][a-z0-9-]{0,31}");

        private FileExecutionRuntimeAdapter {
            language = normalize(language);
            if (!LANGUAGE.matcher(language).matches()) {
                throw new IllegalArgumentException("trusted package language is invalid");
            }
            executablePath = Objects.requireNonNull(executablePath, "executablePath must not be null")
                    .trim();
            if (executablePath.isEmpty()) throw new IllegalArgumentException("executablePath must not be blank");
            invocationArguments = List.copyOf(invocationArguments);
            for (String argument : invocationArguments) {
                if (argument == null || argument.isBlank()) {
                    throw new IllegalArgumentException("invocationArguments must not contain blank items");
                }
            }
        }

        @Override
        public String executable() {
            return executablePath;
        }

        @Override
        public boolean supportsStandardInput() {
            return false;
        }

        @Override
        public boolean supportsFileExecution() {
            return true;
        }

        @Override
        public PreparedScript prepare(String content, List<String> arguments) {
            throw new UnsupportedOperationException("trusted package runtime " + language + " requires entry files");
        }

        @Override
        public PreparedScript prepareFile(String workspaceRelativeEntryPath, List<String> arguments) {
            String entryPath = requireWorkspaceRelativeEntryPath(workspaceRelativeEntryPath);
            List<String> safeArguments = List.copyOf(arguments);
            List<String> argv = new ArrayList<>();
            argv.add(executablePath);
            argv.addAll(invocationArguments);
            argv.add(entryPath);
            for (String argument : safeArguments) {
                argv.add(quotedPowerShellArguments ? quotePowerShell(argument) : argument);
            }
            return new PreparedScript(ExecutionCommand.direct(argv), ExecutionInput.none());
        }
    }

    static String requireWorkspaceRelativeEntryPath(String value) {
        String path = Objects.requireNonNull(value, "workspaceRelativeEntryPath must not be null")
                .trim()
                .replace('\\', '/');
        if (path.indexOf('\0') >= 0 || path.contains("..") || path.startsWith("/") || path.matches("[A-Za-z]:.*")) {
            throw new IllegalArgumentException("trusted package entry path is not workspace-relative");
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty())
                throw new IllegalArgumentException("trusted package entry path has an empty segment");
        }
        return path;
    }
}
