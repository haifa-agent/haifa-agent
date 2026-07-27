package io.haifa.agent.application.project.admin;

public record WorkspaceBindingView(
        String bindingId,
        String redactedLocationRef,
        String mode,
        String status,
        String rootFingerprint,
        long version) {}
