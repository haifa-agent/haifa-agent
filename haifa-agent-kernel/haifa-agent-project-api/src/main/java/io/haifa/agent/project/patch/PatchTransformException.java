package io.haifa.agent.project.patch;

public final class PatchTransformException extends RuntimeException {
    private final int hunkIndex;

    public PatchTransformException(int hunkIndex, String message) {
        super(message);
        this.hunkIndex = hunkIndex;
    }

    public int hunkIndex() {
        return hunkIndex;
    }
}
