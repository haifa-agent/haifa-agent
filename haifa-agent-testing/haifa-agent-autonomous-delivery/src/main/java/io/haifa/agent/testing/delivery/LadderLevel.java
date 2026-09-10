package io.haifa.agent.testing.delivery;

/** Six-level capability ladder for autonomous-delivery regression probes. */
public enum LadderLevel {
    L1(5),
    L2(5),
    L3(4),
    L4(4),
    L5(3),
    L6(2);

    private final int plannedCases;

    LadderLevel(int plannedCases) {
        this.plannedCases = plannedCases;
    }

    public int plannedCases() {
        return plannedCases;
    }

    public String prefix() {
        return name() + "-";
    }
}
