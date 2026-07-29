package io.haifa.agent.execution.core.tool;

import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSchemaValidationError;
import io.haifa.agent.tool.api.ToolSchemaValidationResult;
import io.haifa.agent.tool.api.ToolSchemaValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Adds execution-specific combination checks and conservative hard-deny rules before policy and Approval.
 *
 * <p>This is deliberately not presented as a complete PowerShell/Bash parser. It rejects explicit
 * high-consequence forms and leaves every accepted invocation subject to exact Approval and Broker policy.
 */
public final class ExecutionToolSchemaValidator implements ToolSchemaValidator {
    private static final String INPUT_SCHEMA_ID = "haifa.execution.run.input";
    private static final List<DeniedPattern> HARD_DENIES = List.of(
            denied(
                    "CREDENTIAL_EXPORT",
                    "(?is)(get-childitem\\s+env:|printenv\\b|"
                            + "(api[_-]?key|access[_-]?token|password|credential|secret).{0,80}"
                            + "(write-output|echo|export-clixml|out-file))"),
            denied(
                    "HOST_POWER_OPERATION",
                    "(?i)(\\bshutdown\\b|\\breboot\\b|\\bhalt\\b|restart-computer|stop-computer)"),
            denied("DESTRUCTIVE_FORMAT", "(?i)(format-volume|\\bmkfs(?:\\.[a-z0-9]+)?\\b|\\bformat\\s+[a-z]:)"),
            denied(
                    "SECURITY_CONFIGURATION_CHANGE",
                    "(?i)(set-executionpolicy|set-mppreference|netsh\\s+advfirewall|"
                            + "set-netfirewallprofile|disable-windowsfirewall)"),
            denied("ENCODED_COMMAND", "(?i)(-encodedcommand\\b|-enc\\s+[a-z0-9+/=]{8,}|frombase64string\\s*\\()"),
            denied(
                    "DYNAMIC_DOWNLOAD_EXECUTION",
                    "(?is)(invoke-webrequest|invoke-restmethod|\\bcurl\\b|\\bwget\\b).{0,512}"
                            + "(invoke-expression|\\biex\\b|start-process|\\|\\s*(?:sh|bash|pwsh|powershell)\\b)"),
            denied(
                    "BACKGROUND_RESIDENCY",
                    "(?i)(start-job\\b|register-scheduledtask\\b|schtasks(?:\\.exe)?\\b|"
                            + "nohup\\b|start-process\\b[^\\r\\n]*(?:-windowstyle\\s+hidden|-passthru))"),
            denied(
                    "DESTRUCTIVE_DELETE",
                    "(?is)(remove-item\\b[^\\r\\n]*(?:-recurse[^\\r\\n]*-force|-force[^\\r\\n]*-recurse)"
                            + "|\\brm\\s+-[a-z]*r[a-z]*f|\\brm\\s+-[a-z]*f[a-z]*r|\\bdel\\s+/s\\s+/q)"));

    private final ToolSchemaValidator delegate;

    public ExecutionToolSchemaValidator(ToolSchemaValidator delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public ToolSchemaValidationResult validate(ToolSchema schema, Map<String, Object> instance) {
        ToolSchemaValidationResult base = delegate.validate(schema, instance);
        if (!base.valid() || !INPUT_SCHEMA_ID.equals(schema.id())) return base;

        List<ToolSchemaValidationError> errors = new ArrayList<>();
        String mode = instance.get("mode") instanceof String value ? value.toUpperCase(Locale.ROOT) : "";
        if ("COMMAND".equals(mode)) {
            if (instance.containsKey("language")) {
                errors.add(error("$.language", "combination", "language is only valid for SCRIPT mode"));
            }
            if (instance.containsKey("args")) {
                errors.add(error("$.args", "combination", "args are only valid for SCRIPT mode"));
            }
        } else if ("SCRIPT".equals(mode) && !instance.containsKey("language")) {
            errors.add(error("$.language", "required", "language is required for SCRIPT mode"));
        }

        if (instance.get("content") instanceof String content) {
            for (DeniedPattern denied : HARD_DENIES) {
                if (denied.pattern().matcher(content).find()) {
                    errors.add(error("$.content", "security", "execution content denied: " + denied.reasonCode()));
                    break;
                }
            }
        }
        return errors.isEmpty() ? base : new ToolSchemaValidationResult(errors);
    }

    private static ToolSchemaValidationError error(String path, String keyword, String message) {
        return new ToolSchemaValidationError(path, keyword, message);
    }

    private static DeniedPattern denied(String reasonCode, String expression) {
        return new DeniedPattern(reasonCode, Pattern.compile(expression));
    }

    private record DeniedPattern(String reasonCode, Pattern pattern) {}
}
