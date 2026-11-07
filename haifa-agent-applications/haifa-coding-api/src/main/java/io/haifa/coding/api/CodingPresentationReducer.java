package io.haifa.coding.api;

/** Atomic whole-envelope application; pending requests and UI state live outside confirmed facts. */
public final class CodingPresentationReducer {
    private CodingPresentationReducer() {}

    public static CodingProtocol.Snapshot apply(CodingProtocol.Snapshot current, CodingProtocol.Update update) {
        CodingProtocolChecks.snapshot(current);
        CodingProtocolChecks.snapshot(update.replacement());
        if (!current.scope().viewId().equals(update.viewId()))
            throw new IllegalArgumentException("VIEW_SCOPE_MISMATCH");
        if (!current.scope().equals(update.replacement().scope()))
            throw new IllegalArgumentException("VIEW_SCOPE_MISMATCH");
        if (update.revision() != update.replacement().revision() || update.revision() != update.baseRevision() + 1)
            throw new IllegalArgumentException("INVALID_VIEW_REVISION");
        if (update.revision() <= current.revision()) return current;
        if (update.baseRevision() != current.revision()) throw new IllegalArgumentException("VIEW_GAP_RESYNC");
        return update.replacement();
    }
}
