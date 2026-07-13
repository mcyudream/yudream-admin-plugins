package online.yudream.plugin.aichatbot.application.dto;

import java.util.List;

public record AiChatbotMemoryProfile(String id, String connectionId, String channelId, String userId, String platformUserId,
                                     String nickname, boolean enabled, String summary, List<String> tags,
                                     List<AiChatbotMemoryFact> facts, long updatedAt) { }
