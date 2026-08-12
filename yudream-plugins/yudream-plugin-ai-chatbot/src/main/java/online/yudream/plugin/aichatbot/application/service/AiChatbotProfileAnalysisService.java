package online.yudream.plugin.aichatbot.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotGroupPolicy;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotMemoryFact;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotMemoryProfile;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotProfileAnalysis;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotProfileObservation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Administrator-triggered AI profile analysis built only from bounded user-word observations. */
public class AiChatbotProfileAnalysisService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 90;
    private static final int MAX_TEXT_LENGTH = 1000, MAX_TAGS = 8, MAX_TAG_LENGTH = 50, MAX_FACTS = 8, MAX_FACT_VALUE_LENGTH = 500;
    private static final Set<String> FACT_KEYS = Set.of("preference", "interest", "identity", "note", "habit", "topic", "emotion");
    private static final String SYSTEM_PROMPT = "你是群聊用户画像分析师。根据用户的发言片段分析其人格、兴趣与互动风格。"
            + "只输出一个 JSON 对象，禁止输出 Markdown 代码块或任何额外文字。JSON 结构："
            + "{\"summary\":\"整体画像，100字内\",\"personality\":\"人格与性格特点，100字内\",\"interactionStyle\":\"互动与表达方式，100字内\","
            + "\"tags\":[\"标签\"],\"facts\":[{\"key\":\"interest|preference|identity|habit|topic|emotion|note\",\"value\":\"有发言证据支持的具体事实\"}]}。"
            + "tags 不超过 8 个；facts 不超过 8 条，key 只能取给定值，没有证据时不要编造。";
    private final AiChatbotMemoryProfileService profiles;
    private final AiChatbotPolicyService policies;
    private final PluginAiService ai;
    public AiChatbotProfileAnalysisService(AiChatbotMemoryProfileService profiles, AiChatbotPolicyService policies, PluginAiService ai) { this.profiles = Objects.requireNonNull(profiles, "profiles"); this.policies = Objects.requireNonNull(policies, "policies"); this.ai = Objects.requireNonNull(ai, "ai"); }
    public AiChatbotMemoryProfile analyze(String profileId) {
        AiChatbotMemoryProfile profile = profiles.get(profileId);
        List<AiChatbotProfileObservation> observations = profiles.observations(profile.id());
        if (observations.isEmpty()) throw new IllegalArgumentException("该用户暂无可分析的发言证据");
        AiChatbotGroupPolicy policy = policies.get(profile.connectionId(), profile.channelId());
        PluginAiChatRequest request = new PluginAiChatRequest(SYSTEM_PROMPT, userPrompt(profile, observations), policy.providerCode(), policy.modelCode(), List.of(), null, false);
        PluginAiChatResponse response = await(ai.chat(request));
        if (response == null || response.content() == null || response.content().isBlank()) throw new IllegalStateException("画像分析失败：AI 未返回内容");
        return profiles.applyAnalysis(profile.id(), parse(response.content()));
    }
    AiChatbotProfileAnalysis parse(String content) {
        JsonNode root;
        try { root = MAPPER.readTree(extractJson(content)); } catch (Exception error) { throw new IllegalStateException("画像分析结果不是有效的 JSON", error); }
        if (!root.isObject()) throw new IllegalStateException("画像分析结果不是有效的 JSON 对象");
        return new AiChatbotProfileAnalysis(text(root.get("summary"), MAX_TEXT_LENGTH), text(root.get("personality"), MAX_TEXT_LENGTH), text(root.get("interactionStyle"), MAX_TEXT_LENGTH), tags(root.get("tags")), facts(root.get("facts")));
    }
    private List<String> tags(JsonNode node) { LinkedHashSet<String> result = new LinkedHashSet<>(); if (node != null && node.isArray()) for (JsonNode item : node) { if (!item.isTextual()) continue; String tag = item.asText().trim(); if (tag.isBlank()) continue; result.add(tag.substring(0, Math.min(tag.length(), MAX_TAG_LENGTH))); if (result.size() >= MAX_TAGS) break; } return List.copyOf(result); }
    private List<AiChatbotMemoryFact> facts(JsonNode node) { List<AiChatbotMemoryFact> result = new ArrayList<>(); if (node != null && node.isArray()) for (JsonNode item : node) { if (!item.isObject()) continue; String key = factKey(item.get("key")); String value = text(item.get("value"), MAX_FACT_VALUE_LENGTH); if (value.isBlank()) continue; double confidence = item.has("confidence") && item.get("confidence").isNumber() ? Math.clamp(item.get("confidence").asDouble(), 0d, 1d) : 0.6d; if (result.stream().noneMatch(fact -> fact.key().equals(key))) result.add(new AiChatbotMemoryFact(key, value, confidence, false, 0)); if (result.size() >= MAX_FACTS) break; } return result; }
    private String factKey(JsonNode node) { String key = node != null && node.isTextual() ? node.asText().trim().toLowerCase(Locale.ROOT) : ""; return FACT_KEYS.contains(key) ? key : "note"; }
    private String userPrompt(AiChatbotMemoryProfile profile, List<AiChatbotProfileObservation> observations) { StringBuilder builder = new StringBuilder("用户昵称：").append(profile.nickname().isBlank() ? "未知" : profile.nickname()).append("\n历史画像：").append(profile.summary().isBlank() ? "无" : profile.summary()).append("\n发言片段（最新在前，每条已截断）："); observations.forEach(observation -> builder.append("\n- ").append(observation.content())); return builder.toString(); }
    private String extractJson(String content) { String value = content == null ? "" : content.trim(); int start = value.indexOf('{'), end = value.lastIndexOf('}'); if (start < 0 || end <= start) throw new IllegalStateException("画像分析结果缺少 JSON 内容"); return value.substring(start, end + 1); }
    private PluginAiChatResponse await(CompletionStage<PluginAiChatResponse> stage) { try { return stage.toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS); } catch (Exception error) { Throwable cause = error instanceof java.util.concurrent.ExecutionException && error.getCause() != null ? error.getCause() : error; throw new IllegalStateException("画像分析调用失败：" + (cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage()), cause); } }
    private static String text(JsonNode node, int max) { String value = node != null && node.isTextual() ? node.asText().trim() : ""; return value.substring(0, Math.min(value.length(), max)); }
}
