package online.yudream.plugin.aichatbot.interfaces.request;

import java.util.List;
public record AiChatbotGroupPolicyBatchSaveRequest(List<String> connectionIds, List<String> channelIds, AiChatbotGroupPolicySaveRequest policy) { }
