package online.yudream.plugin.aichatbot.interfaces.http;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotGroupPolicy;
import online.yudream.plugin.aichatbot.application.service.AiChatbotActivityService;
import online.yudream.plugin.aichatbot.application.service.AiChatbotMemoryProfileService;
import online.yudream.plugin.aichatbot.application.service.AiChatbotProfileAnalysisService;
import online.yudream.plugin.aichatbot.application.service.AiChatbotPolicyService;
import online.yudream.plugin.aichatbot.interfaces.request.AiChatbotGroupPolicyBatchSaveRequest;
import online.yudream.plugin.aichatbot.interfaces.request.AiChatbotGroupPolicySaveRequest;
import online.yudream.plugin.aichatbot.interfaces.request.AiChatbotMemoryProfileUpdateRequest;
import online.yudream.plugin.aichatbot.interfaces.support.JsonSupport;

import java.time.ZoneId;

public class AiChatbotHttpFacade {
    private final AiChatbotPolicyService policies; private final FrameworkServices framework; private final AiChatbotMemoryProfileService profiles; private final AiChatbotActivityService activities; private final AiChatbotProfileAnalysisService analysis;
    public AiChatbotHttpFacade(AiChatbotPolicyService policies, AiChatbotMemoryProfileService profiles, AiChatbotActivityService activities, AiChatbotProfileAnalysisService analysis, FrameworkServices framework) { this.policies = policies; this.profiles = profiles; this.activities = activities; this.analysis = analysis; this.framework = framework; }
    public PluginHttpResponse policies() { return PluginHttpResponse.ok(policies.list()); }
    public PluginHttpResponse policy(PluginHttpRequest request) { return PluginHttpResponse.ok(policies.get(query(request, "connectionId"), query(request, "channelId"))); }
    public PluginHttpResponse save(PluginHttpRequest request) { AiChatbotGroupPolicySaveRequest body = JsonSupport.read(request.body(), AiChatbotGroupPolicySaveRequest.class); return PluginHttpResponse.ok(policies.save(policy(body, body.connectionId(), body.channelId()))); }
    public PluginHttpResponse saveBatch(PluginHttpRequest request) { AiChatbotGroupPolicyBatchSaveRequest body = JsonSupport.read(request.body(), AiChatbotGroupPolicyBatchSaveRequest.class); if (body.connectionIds() == null || body.channelIds() == null || body.connectionIds().isEmpty() || body.channelIds().isEmpty()) throw new IllegalArgumentException("至少选择一个连接和一个群聊"); return PluginHttpResponse.ok(body.connectionIds().stream().flatMap(connectionId -> body.channelIds().stream().map(channelId -> policies.save(policy(body.policy(), connectionId, channelId)))).toList()); }
    public PluginHttpResponse connections() { return PluginHttpResponse.ok(framework.messaging().connections()); }
    public PluginHttpResponse groups(PluginHttpRequest request) { return PluginHttpResponse.ok(framework.messaging().groups(query(request, "connectionId"))); }
    public PluginHttpResponse tools() { return PluginHttpResponse.ok(framework.ai().tools()); }
    public PluginHttpResponse agents() { return PluginHttpResponse.ok(framework.ai().agents()); }
    public PluginHttpResponse profiles(PluginHttpRequest request) { return PluginHttpResponse.ok(profiles.page(intQuery(request, "page", 1), intQuery(request, "size", 10))); }
    public PluginHttpResponse profile(PluginHttpRequest request) { return PluginHttpResponse.ok(profiles.get(query(request, "id"))); }
    public PluginHttpResponse saveProfile(PluginHttpRequest request) { return PluginHttpResponse.ok(profiles.update(JsonSupport.read(request.body(), AiChatbotMemoryProfileUpdateRequest.class))); }
    public PluginHttpResponse profileEnabled(PluginHttpRequest request) { return PluginHttpResponse.ok(profiles.enabled(query(request, "id"), Boolean.parseBoolean(query(request, "enabled")))); }
    public PluginHttpResponse deleteProfile(PluginHttpRequest request) { profiles.delete(query(request, "id")); return PluginHttpResponse.ok(java.util.Map.of("deleted", true)); }
    public PluginHttpResponse analyzeProfile(PluginHttpRequest request) { return PluginHttpResponse.ok(analysis.analyze(query(request, "id"))); }
    public PluginHttpResponse profileObservations(PluginHttpRequest request) { return PluginHttpResponse.ok(profiles.observations(query(request, "id"))); }
    public PluginHttpResponse overview(PluginHttpRequest request) { Query query = activityQuery(request); return PluginHttpResponse.ok(activities.overview(query.from, query.to, query.connectionId, query.channelId, query.type, query.user)); }
    public PluginHttpResponse timeline(PluginHttpRequest request) { Query query = activityQuery(request); return PluginHttpResponse.ok(activities.timeline(query.from, query.to, query.connectionId, query.channelId, query.type, query.user, query.bucket, query.zone)); }
    public PluginHttpResponse heatmap(PluginHttpRequest request) { Query query = activityQuery(request); return PluginHttpResponse.ok(activities.heatmap(query.from, query.to, query.connectionId, query.channelId, query.type, query.user, query.zone)); }
    public PluginHttpResponse users(PluginHttpRequest request) { Query query = activityQuery(request); return PluginHttpResponse.ok(activities.users(query.from, query.to, query.connectionId, query.channelId, query.type, query.user)); }
    public PluginHttpResponse events(PluginHttpRequest request) { Query query = activityQuery(request); return PluginHttpResponse.ok(activities.page(query.from, query.to, query.connectionId, query.channelId, query.type, query.user, intQuery(request, "page", 1), intQuery(request, "size", 20))); }
    private Query activityQuery(PluginHttpRequest request) { Long from = strictLong(request, "from"), to = strictLong(request, "to"); if (from != null && to != null && from > to) throw new IllegalArgumentException("from不能晚于to"); String bucket = value(request, "bucket", "day"); if (!"day".equals(bucket) && !"hour".equals(bucket)) throw new IllegalArgumentException("bucket仅支持hour或day"); String timezone = value(request, "timezone", "UTC"); try { return new Query(from, to, query(request, "connectionId"), query(request, "channelId"), query(request, "type"), query(request, "user"), bucket, ZoneId.of(timezone)); } catch (Exception error) { throw new IllegalArgumentException("timezone无效"); } }
    private AiChatbotGroupPolicy policy(AiChatbotGroupPolicySaveRequest body, String connectionId, String channelId) { return new AiChatbotGroupPolicy(connectionId, channelId, body.enabled(), body.randomProbability(), body.groupContextLimit(), body.personalContextLimit(), body.contextExpansionLimit(), body.cooldownSeconds(), body.hourlyReplyLimit(), body.quietHoursStart(), body.quietHoursEnd(), body.systemPrompt(), body.persona(), body.enabledToolNames(), body.randomToolCallingEnabled(), body.longTermMemoryEnabled(), body.semanticMemoryTopK(), body.agentCode(), body.providerCode(), body.modelCode()); }
    private String query(PluginHttpRequest request, String key) { var values = request.query().get(key); return values == null || values.isEmpty() ? null : values.getFirst(); }
    private String value(PluginHttpRequest request, String key, String fallback) { String value = query(request, key); return value == null || value.isBlank() ? fallback : value; }
    private int intQuery(PluginHttpRequest request, String key, int fallback) { try { return Integer.parseInt(query(request, key)); } catch (Exception ignored) { return fallback; } }
    private Long strictLong(PluginHttpRequest request, String key) { String value = query(request, key); if (value == null || value.isBlank()) return null; try { return Long.parseLong(value); } catch (NumberFormatException error) { throw new IllegalArgumentException(key + "必须为毫秒时间戳"); } }
    private record Query(Long from, Long to, String connectionId, String channelId, String type, String user, String bucket, ZoneId zone) { }
}
