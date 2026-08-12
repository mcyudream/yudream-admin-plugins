package online.yudream.plugin.aichatbot.application.dto;

/** Bounded evidence of the user's own words for profile analysis; AI output is never stored here. */
public record AiChatbotProfileObservation(String content, long occurredAt) { }
