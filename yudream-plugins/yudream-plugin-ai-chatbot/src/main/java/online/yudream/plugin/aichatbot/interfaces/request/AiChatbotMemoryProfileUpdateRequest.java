package online.yudream.plugin.aichatbot.interfaces.request;

import online.yudream.plugin.aichatbot.application.dto.AiChatbotMemoryFact;

import java.util.List;

public record AiChatbotMemoryProfileUpdateRequest(String id, boolean enabled, String summary, List<String> tags,
                                                  List<AiChatbotMemoryFact> facts) { }
