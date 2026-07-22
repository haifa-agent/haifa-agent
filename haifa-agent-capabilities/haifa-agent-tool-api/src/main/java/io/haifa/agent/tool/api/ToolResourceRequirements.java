package io.haifa.agent.tool.api;

import java.util.Set;

public record ToolResourceRequirements(
        Set<String> filesystemCapabilities, Set<String> networkHosts, Set<String> executionProfiles) {
    public ToolResourceRequirements {
        filesystemCapabilities = ToolValues.set(filesystemCapabilities, "filesystemCapabilities");
        networkHosts = ToolValues.set(networkHosts, "networkHosts");
        executionProfiles = ToolValues.set(executionProfiles, "executionProfiles");
    }

    public static ToolResourceRequirements none() {
        return new ToolResourceRequirements(Set.of(), Set.of(), Set.of());
    }
}
