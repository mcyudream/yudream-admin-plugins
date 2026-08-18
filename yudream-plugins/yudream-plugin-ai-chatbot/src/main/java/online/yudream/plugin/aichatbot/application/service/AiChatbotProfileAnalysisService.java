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
            + "画像采用沉淀式更新：以提供的现有画像为底稿，结合新发言证据核对每一项——仍准确的保留原样，不准确的修正，缺失的补充，事实过期或与证据矛盾时更新该事实；不要把画像推倒重写。"
            + "只输出一个 JSON 对象，禁止输出 Markdown 代码块或任何额外文字。JSON 结构："
            + "{\"summary\":\"整体画像，100字内\",\"personality\":\"人格与性格特点，100字内\",\"interactionStyle\":\"互动与表达方式，100字内\","
            + "\"tags\":[\"标签\"],\"facts\":[{\"key\":\"interest|preference|identity|habit|topic|emotion|note\",\"value\":\"有发言证据支持的具体事实\"}]}。"
            + "tags 输出修正后的完整列表，不超过 8 个；facts 不超过 8 条，key 只能取给定值，没有证据时不要编造。";
    private final AiChatbotMemoryProfileService profiles;
    private final AiChatbotPolicyService policies;
    private final PluginAiService ai;
    private final AiChatbotMessageLogService messageLogs;
    private final java.util.Random random = new java.util.Random();
    public AiChatbotProfileAnalysisService(AiChatbotMemoryProfileService profiles, AiChatbotPolicyService policies, PluginAiService ai) { this(profiles, policies, ai, null); }
    public AiChatbotProfileAnalysisService(AiChatbotMemoryProfileService profiles, AiChatbotPolicyService policies, PluginAiService ai, AiChatbotMessageLogService messageLogs) { this.profiles = Objects.requireNonNull(profiles, "profiles"); this.policies = Objects.requireNonNull(policies, "policies"); this.ai = Objects.requireNonNull(ai, "ai"); this.messageLogs = messageLogs; }
    public AiChatbotMemoryProfile analyze(String profileId) {
        AiChatbotMemoryProfile profile = profiles.get(profileId);
        AiChatbotGroupPolicy policy = policies.get(profile.connectionId(), profile.channelId());
        int sampleSize = policy.profileAnalysisMessageCount();
        // 证据源一：机器人互动观察（随机回复、@ 消息），超限随机抽取
        List<AiChatbotProfileObservation> observations = randomCap(profiles.observations(profile.id()), sampleSize);
        if (observations.isEmpty()) {
            // 兼容新版之前建立的画像：回退使用用户本人发言的 recent_message 事实作为证据
            observations = profile.facts().stream()
                    .filter(fact -> "recent_message".equals(fact.key()) && fact.value() != null && !fact.value().isBlank())
                    .map(fact -> new AiChatbotProfileObservation(fact.value(), fact.updatedAt()))
                    .toList();
        }
        // 证据源二：近一天群消息库随机抽取，超限同样随机抽样
        List<AiChatbotMessageLogService.LoggedMessage> recentMessages = messageLogs == null
                ? List.of()
                : messageLogs.sample(profile.connectionId(), profile.channelId(), profile.userId(), sampleSize);
        if (observations.isEmpty() && recentMessages.isEmpty()) throw new IllegalArgumentException("该用户暂无可分析的发言证据，请先让该用户在群里发言或与机器人互动");
        PluginAiChatRequest request = new PluginAiChatRequest(SYSTEM_PROMPT, userPrompt(profile, observations, recentMessages), blankToNull(policy.effectiveProfileProviderCode()), blankToNull(policy.effectiveProfileModelCode()), List.of(), null, false);
        PluginAiChatResponse response = await(ai.chat(request));
        if (response == null || response.content() == null || response.content().isBlank()) throw new IllegalStateException("画像分析失败：AI 未返回内容，请检查宿主的默认 AI 模型配置");
        return profiles.applyAnalysis(profile.id(), parse(response.content()));
    }
    /** 批量分析的证据条数：互动观察 + 近一天消息库合计，用于“条数小于配置数则不分析”判定。 */
    public long evidenceCount(AiChatbotMemoryProfile profile) {
        long observations = profiles.observations(profile.id()).size();
        long messages = messageLogs == null ? 0 : messageLogs.countRecent(profile.connectionId(), profile.channelId(), profile.userId());
        return observations + messages;
    }
    private <T> List<T> randomCap(List<T> source, int limit) {
        if (source == null || source.isEmpty()) return List.of();
        if (source.size() <= limit) return source;
        List<T> shuffled = new ArrayList<>(source);
        java.util.Collections.shuffle(shuffled, random);
        return List.copyOf(shuffled.subList(0, limit));
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
    private String userPrompt(AiChatbotMemoryProfile profile, List<AiChatbotProfileObservation> observations, List<AiChatbotMessageLogService.LoggedMessage> recentMessages) {
        StringBuilder builder = new StringBuilder("用户昵称：").append(profile.nickname().isBlank() ? "未知" : profile.nickname());
        // 现有画像全量提供作为底稿，供模型沉淀修正而非重写
        builder.append("\n现有画像（作为底稿，逐项核对后输出修正后的完整画像）：");
        builder.append("\n- 摘要：").append(profile.summary().isBlank() ? "无" : profile.summary());
        builder.append("\n- 人格：").append(profile.personality().isBlank() ? "无" : profile.personality());
        builder.append("\n- 互动风格：").append(profile.interactionStyle().isBlank() ? "无" : profile.interactionStyle());
        builder.append("\n- 标签：").append(profile.tags().isEmpty() ? "无" : String.join("、", profile.tags()));
        List<AiChatbotMemoryFact> existingFacts = profile.facts().stream()
                .filter(fact -> !"recent_message".equals(fact.key()) && fact.value() != null && !fact.value().isBlank())
                .toList();
        if (!existingFacts.isEmpty()) {
            builder.append("\n- 既有事实：");
            existingFacts.forEach(fact -> builder.append("\n  * ").append(fact.key()).append("：").append(fact.value())
                    .append(fact.approved() ? "（管理员已确认，除非有明确反证否则不要改动）" : ""));
        }
        if (!observations.isEmpty()) { builder.append("\n发言片段（来自与该用户的互动记录，随机抽取）："); observations.forEach(observation -> builder.append("\n- ").append(observation.content())); }
        if (!recentMessages.isEmpty()) { builder.append("\n近期群聊发言（近 24 小时随机抽取）："); recentMessages.forEach(message -> builder.append("\n- ").append(message.content())); }
        return builder.toString();
    }
    private String extractJson(String content) { String value = content == null ? "" : content.trim(); int start = value.indexOf('{'), end = value.lastIndexOf('}'); if (start < 0 || end <= start) throw new IllegalStateException("画像分析结果缺少 JSON 内容"); return value.substring(start, end + 1); }
    private PluginAiChatResponse await(CompletionStage<PluginAiChatResponse> stage) { try { return stage.toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS); } catch (Exception error) { Throwable cause = error instanceof java.util.concurrent.ExecutionException && error.getCause() != null ? error.getCause() : error; throw new IllegalStateException("画像分析调用失败：" + (cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage()), cause); } }
    private static String text(JsonNode node, int max) { String value = node != null && node.isTextual() ? node.asText().trim() : ""; return value.substring(0, Math.min(value.length(), max)); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
