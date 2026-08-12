package online.yudream.plugin.aichatbot.application.dto;

/** Contains only operational metadata; never message, prompt, AI output, or error details. */
public record AiChatbotActivityEvent(String id, long occurredAt, String connectionId, String channelId,
                                     String platformUserId, String userId, String type, String mode,
                                     boolean success) { }
