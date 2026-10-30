package io.haifa.agent.sandbox.docker;

/** Docker Desktop drive aliases, used both for bind creation and exact mount verification. */
final class DockerDesktopPaths {
    private DockerDesktopPaths() {}

    static String bindSource(String hostPath) {
        String path = hostPath.replace('\\', '/');
        if (path.matches("^[A-Za-z]:/.*")) {
            return "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2);
        }
        if (path.startsWith("//")) {
            throw new IllegalArgumentException("Docker workspace UNC paths are not supported");
        }
        return hostPath;
    }

    static boolean sameMount(String hostPath, String inspectedSource) {
        if (hostPath.equals(inspectedSource)) return true;
        String expected = bindSource(hostPath);
        if (expected.equals(hostPath) || inspectedSource == null) return false;
        String actual = inspectedSource.replace('\\', '/');
        for (String prefix : java.util.List.of("/run/desktop/mnt/host", "/host_mnt")) {
            if (actual.startsWith(prefix + "/")) {
                actual = actual.substring(prefix.length());
                break;
            }
        }
        return expected.equals(bindSource(actual));
    }
}
