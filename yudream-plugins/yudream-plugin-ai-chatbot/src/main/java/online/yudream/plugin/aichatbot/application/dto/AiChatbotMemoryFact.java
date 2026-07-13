package online.yudream.plugin.aichatbot.application.dto;

public record AiChatbotMemoryFact(String key, String value, double confidence, boolean approved, long updatedAt) { }
