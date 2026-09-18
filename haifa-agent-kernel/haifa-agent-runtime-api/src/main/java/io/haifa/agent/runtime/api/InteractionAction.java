package io.haifa.agent.runtime.api;

/** Forward-compatible public action submitted for an interaction. */
public record InteractionAction(String value) {
    public static final InteractionAction SUBMIT = new InteractionAction("submit");
    public static final InteractionAction CANCEL = new InteractionAction("cancel");
    public static final InteractionAction CONFIRM = new InteractionAction("confirm");
    public static final InteractionAction REJECT = new InteractionAction("reject");
    public static final InteractionAction APPROVE = new InteractionAction("approve");

    public InteractionAction {
        value = InteractionKind.requireToken(value, "value");
    }
}
