package online.yudream.plugin.aichatbot.interfaces.http;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotGroupPolicy;
import online.yudream.plugin.aichatbot.application.service.AiChatbotPolicyService;
import online.yudream.plugin.aichatbot.interfaces.request.AiChatbotGroupPolicySaveRequest;
import online.yudream.plugin.aichatbot.interfaces.request.AiChatbotGroupPolicyBatchSaveRequest;
import online.yudream.plugin.aichatbot.interfaces.support.JsonSupport;
import online.yudream.base.plugin.spi.system.FrameworkServices;

public class AiChatbotHttpFacade {
    private final AiChatbotPolicyService policies;
    private final FrameworkServices framework;
    public AiChatbotHttpFacade(AiChatbotPolicyService policies, FrameworkServices framework) { this.policies = policies; this.framework = framework; }
    public PluginHttpResponse policies() { return PluginHttpResponse.ok(policies.list()); }
    public PluginHttpResponse policy(PluginHttpRequest request) { return PluginHttpResponse.ok(policies.get(query(request, "connectionId"), query(request, "channelId"))); }
    public PluginHttpResponse save(PluginHttpRequest request) {
        AiChatbotGroupPolicySaveRequest body = JsonSupport.read(request.body(), AiChatbotGroupPolicySaveRequest.class);
        return PluginHttpResponse.ok(policies.save(policy(body, body.connectionId(), body.channelId())));
    }
    public PluginHttpResponse saveBatch(PluginHttpRequest request) {
        AiChatbotGroupPolicyBatchSaveRequest body = JsonSupport.read(request.body(), AiChatbotGroupPolicyBatchSaveRequest.class);
        if (body.connectionIds() == null || body.channelIds() == null || body.connectionIds().isEmpty() || body.channelIds().isEmpty()) throw new IllegalArgumentException("至少选择一个连接和一个群聊");
        return PluginHttpResponse.ok(body.connectionIds().stream().flatMap(connectionId -> body.channelIds().stream().map(channelId -> policies.save(policy(body.policy(), connectionId, channelId)))).toList());
    }
    public PluginHttpResponse connections() { return PluginHttpResponse.ok(framework.messaging().connections()); }
    public PluginHttpResponse groups(PluginHttpRequest request) { return PluginHttpResponse.ok(framework.messaging().groups(query(request, "connectionId"))); }
    public PluginHttpResponse tools() { return PluginHttpResponse.ok(framework.ai().tools()); }
    public PluginHttpResponse providers() { return PluginHttpResponse.ok(framework.ai().providers()); }
    private AiChatbotGroupPolicy policy(AiChatbotGroupPolicySaveRequest body, String connectionId, String channelId) { return new AiChatbotGroupPolicy(connectionId, channelId, body.enabled(), body.randomProbability(), body.groupContextLimit(), body.personalContextLimit(), body.contextExpansionLimit(), body.cooldownSeconds(), body.hourlyReplyLimit(), body.quietHoursStart(), body.quietHoursEnd(), body.systemPrompt(), body.persona(), body.enabledToolNames(), body.providerCode(), body.modelCode()); }
    private String query(PluginHttpRequest request, String key) { var values = request.query().get(key); return values == null || values.isEmpty() ? null : values.getFirst(); }
}
