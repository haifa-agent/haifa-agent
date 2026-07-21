package io.haifa.agent.project.provider.local;

import io.haifa.agent.project.path.ProjectPath;
import java.util.Locale;
import java.util.Set;

@FunctionalInterface
public interface SensitivePathPolicy {
    boolean mayRead(ProjectPath path);

    static SensitivePathPolicy defaults() {
        Set<String> deniedNames = Set.of(".env", "id_rsa", "id_ed25519", "credentials", "credentials.json");
        return path -> {
            String lower = path.value().toLowerCase(Locale.ROOT);
            if (lower.equals(".git/config") || lower.startsWith(".ssh/") || lower.startsWith(".haifa-")) {
                return false;
            }
            if (path.segments().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .anyMatch(value -> value.startsWith(".haifa-"))) {
                return false;
            }
            return path.segments().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .noneMatch(deniedNames::contains);
        };
    }
}
