package online.yudream.plugin.aichatbot.application.dto;

import java.util.List;

public record AiChatbotMemoryProfile(String id, String connectionId, String channelId, String userId, String platformUserId,
                                     String nickname, String avatar, boolean enabled, String summary, String personality, String interactionStyle,
                                     List<String> tags, List<AiChatbotMemoryFact> facts, long observedMessageCount, long replyTriggeredCount,
                                     long replyCompletedCount, long replyFailedCount, long lastActivityAt, long lastAnalyzedAt, long updatedAt) { }
