package io.haifa.agent.model.api;

/** Built-in API Style identifiers and their internal adapter coordinates. */
public final class ModelApiStyles {
    public static final ApiStyleId OPENAI_CHAT_COMPLETIONS = new ApiStyleId("openai-chat-completions");
    public static final ApiStyleId OPENAI_RESPONSES = new ApiStyleId("openai-responses");
    public static final ApiStyleId ANTHROPIC_MESSAGES = new ApiStyleId("anthropic-messages");
    public static final ApiStyleId GOOGLE_GEMINI_GENERATE_CONTENT = new ApiStyleId("google-gemini-generate-content");
    public static final ApiStyleId DETERMINISTIC_CHAT = new ApiStyleId("deterministic-chat");

    public static final String OPENAI_CHAT_ADAPTER = "openai-compatible";
    public static final String OPENAI_RESPONSES_ADAPTER = "openai-responses-compatible";
    public static final String ANTHROPIC_MESSAGES_ADAPTER = "anthropic-messages-compatible";
    public static final String GOOGLE_GEMINI_ADAPTER = "google-gemini";
    public static final String DETERMINISTIC_CHAT_ADAPTER = "deterministic-chat";

    private ModelApiStyles() {}

    public static String adapterType(ApiStyleId style) {
        if (OPENAI_CHAT_COMPLETIONS.equals(style)) return OPENAI_CHAT_ADAPTER;
        if (OPENAI_RESPONSES.equals(style)) return OPENAI_RESPONSES_ADAPTER;
        if (ANTHROPIC_MESSAGES.equals(style)) return ANTHROPIC_MESSAGES_ADAPTER;
        if (GOOGLE_GEMINI_GENERATE_CONTENT.equals(style)) return GOOGLE_GEMINI_ADAPTER;
        if (DETERMINISTIC_CHAT.equals(style)) return DETERMINISTIC_CHAT_ADAPTER;
        throw new IllegalArgumentException("unsupported API style: " + style);
    }
}
