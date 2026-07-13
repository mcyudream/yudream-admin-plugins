package online.yudream.plugin.aichatbot.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginCommand;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatMessage;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.memory.PluginSemanticMemoryQuery;
import online.yudream.base.plugin.spi.system.memory.PluginSemanticMemoryRecord;
import online.yudream.base.plugin.spi.system.command.PluginCommandContext;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginInteractionFilter;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.plugin.aichatbot.application.dto.AiChatbotGroupPolicy;
import online.yudream.plugin.aichatbot.application.service.AiChatbotPolicyService;
import online.yudream.plugin.aichatbot.application.service.AiChatbotMemoryProfileService;
import online.yudream.plugin.aichatbot.interfaces.controller.AiChatbotController;
import online.yudream.plugin.aichatbot.interfaces.http.AiChatbotHttpFacade;
import online.yudream.plugin.aichatbot.interfaces.tool.AiChatbotCurrentUserTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

@PluginSpec(code = AiChatbotPlugin.CODE, name = "ai-chatbot", version = "1.0.0", description = "群聊 AI 回复与用户专属上下文。")
@PluginPermissions({@PluginPermission(code = AiChatbotPlugin.USE_PERMISSION, name = "使用 AI 群聊", module = "平台插件", description = "@机器人进行 AI 对话"), @PluginPermission(code = AiChatbotPlugin.MANAGE_PERMISSION, name = "管理 AI 群聊", module = "平台插件", description = "管理群聊机器人策略")})
@PluginFrontend(moduleName = "aiChatbot", menuTitle = "AI 群聊机器人", menuIcon = "i-ri:robot-2-line", menuSort = 65, routes = {
        @PluginRoute(path = "/platform/plugins/ai-chatbot/admin/settings", name = "platform-plugin-ai-chatbot-settings", title = "群聊配置", icon = "i-ri:settings-3-line", component = "ai-chatbot/Settings", permission = AiChatbotPlugin.MANAGE_PERMISSION, sort = 10),
        @PluginRoute(path = "/platform/plugins/ai-chatbot/admin/memory-profiles", name = "platform-plugin-ai-chatbot-memory-profiles", title = "记忆画像", icon = "i-ri:brain-line", component = "ai-chatbot/MemoryProfiles", permission = AiChatbotPlugin.MANAGE_PERMISSION, sort = 20)
})
public class AiChatbotPlugin implements YuDreamPlugin {
    public static final String CODE = "ai-chatbot";
    public static final String USE_PERMISSION = "plugin:ai-chatbot:use";
    public static final String MANAGE_PERMISSION = "plugin:ai-chatbot:manage";
    private static final Logger LOGGER = Logger.getLogger(AiChatbotPlugin.class.getName());
    private final Map<String, CompletableFuture<Void>> groupQueues = new ConcurrentHashMap<>();
    private PluginContext context;
    private AiChatbotPolicyService policies;

    @Override public void onEnable(PluginContext context) {
        this.context = context; policies = new AiChatbotPolicyService(context.documents());
        context.registerHttpController(new AiChatbotController(new AiChatbotHttpFacade(policies, new AiChatbotMemoryProfileService(context.documents()), context.framework())));
        context.registerAiTool(new AiChatbotCurrentUserTool(context.framework().users()));
        context.interactions().onMessage(new PluginInteractionFilter(Set.of("message_receive"), "milky", null, null), this::onMessage);
    }

    @PluginCommand(code = "ai-chatbot.clear", command = "清空AI记忆", name = "清空 AI 记忆", description = "清空当前群内的个人 AI 对话记忆", permission = USE_PERMISSION)
    public void clear(PluginCommandContext command, PluginContext ignored) {
        if (command.userId() == null) { reply(command.event(), "当前 QQ 未绑定系统账号。"); return; }
        context.documents().delete("user-memory", memoryId(command.event(), command.userId())); reply(command.event(), "已清空当前群内的 AI 专属记忆。");
    }

    private void onMessage(PluginEvent event) {
        if (event.userId() == null || event.userId().equals(event.selfId()) || event.content() == null || event.content().isBlank()) return;
        String key = groupId(event);
        CompletableFuture<Void> next = groupQueues.compute(key, (ignored, tail) -> {
            CompletableFuture<Void> previous = tail == null ? CompletableFuture.completedFuture(null) : tail.exceptionally(error -> null);
            return previous.thenCompose(value -> processMessage(event));
        });
        next.whenComplete((value, error) -> groupQueues.remove(key, next));
    }

    private CompletableFuture<Void> processMessage(PluginEvent event) {
        AiChatbotGroupPolicy policy = policies.get(event.connectionId(), event.channelId());
        boolean mentioned = mentions(event).contains(event.selfId());
        append("group-history", groupId(event), "user", event.userId() + "：" + event.content());
        boolean random = !mentioned && ThreadLocalRandom.current().nextDouble() < policy.randomProbability();
        if ((!mentioned && !random) || !policies.allowReply(policy, System.currentTimeMillis(), mentioned)) return CompletableFuture.completedFuture(null);
        LOGGER.info(() -> "[YuDreamAdmin] [AI Chatbot] reply triggered: connection=" + event.connectionId() + ", channel=" + event.channelId() + ", mode=" + (mentioned ? "MENTION" : "RANDOM"));
        if (mentioned && asksForTools(event.content())) { reply(event, toolSummary(policy)); return CompletableFuture.completedFuture(null); }
        Long userId = context.framework().users().findByQq(event.userId()).map(profile -> profile.id()).orElse(null);
        if (mentioned && userId == null) { reply(event, "请先绑定系统账号后再使用 AI 对话。"); return CompletableFuture.completedFuture(null); }
        List<PluginAiChatMessage> history = history(mentioned && userId != null ? "user-memory" : "group-history", mentioned && userId != null ? memoryId(event, userId) : groupId(event), mentioned && userId != null ? policy.personalContextLimit() : policy.groupContextLimit());
        if (userId != null && policy.longTermMemoryEnabled()) {
            String namespace = groupId(event) + ":" + userId;
            try {
                var hits = context.semanticMemory().search(new PluginSemanticMemoryQuery(namespace, event.content(), policy.providerCode(), policy.modelCode(), policy.semanticMemoryTopK())).toCompletableFuture().join();
                if (!hits.isEmpty()) { history = new ArrayList<>(history); history.addAll(hits.stream().map(hit -> new PluginAiChatMessage("system", "相关长期记忆：" + hit.content())).toList()); }
                context.semanticMemory().index(new PluginSemanticMemoryRecord(namespace, event.messageId() == null ? UUID.randomUUID().toString() : event.messageId(), event.content(), policy.providerCode(), policy.modelCode(), Map.of("qq", event.userId(), "timestamp", System.currentTimeMillis())));
            } catch (Exception ignored) { }
        }
        if (mentioned && userId != null && (mentions(event).stream().anyMatch(id -> !id.equals(event.selfId())) || hasReply(event))) { List<PluginAiChatMessage> expanded = new ArrayList<>(history("group-history", groupId(event), policy.contextExpansionLimit())); expanded.addAll(history); history = expanded; }
        String mode = mentioned ? "MENTION" : "RANDOM";
        PluginAiExecutionContext execution = new PluginAiExecutionContext(userId, event.userId(), event.connectionId(), event.channelId(), event.messageId(), mode, UUID.randomUUID().toString(), List.of(), policy.enabledToolNames());
        return context.framework().ai().chat(new PluginAiChatRequest(prompt(mode, policy), event.content(), blankToNull(policy.providerCode()), blankToNull(policy.modelCode()), history, execution, policy.toolCallingEnabled(mode))).handle((result, error) -> {
            if (error != null) { LOGGER.warning("[YuDreamAdmin] [AI Chatbot] reply failed: " + errorMessage(error)); reply(event, "AI 请求失败：" + errorMessage(error)); return (Void) null; }
            if (result == null || result.content() == null || result.content().isBlank()) { LOGGER.warning("[YuDreamAdmin] [AI Chatbot] reply failed: empty AI content"); reply(event, "AI 未返回可发送的内容。"); return (Void) null; }
            if (result.content().contains("<tool_calls>") || result.content().contains("<invoke name=")) { LOGGER.warning("[YuDreamAdmin] [AI Chatbot] reply failed: unrecognized tool call format"); reply(event, "AI 服务返回了未识别的工具调用格式，本次操作未执行。请检查所选模型是否支持原生工具调用。"); return (Void) null; }
            append("group-history", groupId(event), "assistant", result.content());
            if (mentioned && userId != null) { append("user-memory", memoryId(event, userId), "user", event.content()); append("user-memory", memoryId(event, userId), "assistant", result.content()); }
            policies.recordReply(policy, System.currentTimeMillis()); reply(event, result.content()); LOGGER.info("[YuDreamAdmin] [AI Chatbot] reply completed: connection=" + event.connectionId() + ", channel=" + event.channelId() + ", mode=" + mode); return (Void) null;
        }).toCompletableFuture();
    }

    private List<PluginAiChatMessage> history(String collection, String id, int limit) { return context.documents().findById(collection, id).map(doc -> toHistory(doc.get("messages"), limit)).orElse(List.of()); }
    private List<PluginAiChatMessage> toHistory(Object value, int limit) { if (!(value instanceof List<?> rows)) return List.of(); List<PluginAiChatMessage> result = new ArrayList<>(); for (Object row : rows) if (row instanceof Map<?, ?> map) result.add(new PluginAiChatMessage(String.valueOf(map.get("role")), String.valueOf(map.get("content")))); return result.size() > limit ? result.subList(result.size() - limit, result.size()) : result; }
    @SuppressWarnings("unchecked") private void append(String collection, String id, String role, String content) { List<Map<String, String>> values = new ArrayList<>(); context.documents().findById(collection, id).map(doc -> doc.get("messages")).filter(List.class::isInstance).map(List.class::cast).ifPresent(values::addAll); values.add(Map.of("role", role, "content", content)); if (values.size() > 32) values = new ArrayList<>(values.subList(values.size() - 32, values.size())); context.documents().save(collection, id, Map.of("messages", values, "updatedAt", System.currentTimeMillis())); }
    private List<String> mentions(PluginEvent event) { Object value = event.referrer().get("mentions"); return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(); }
    private boolean hasReply(PluginEvent event) { Object value = event.referrer().get("replyMessageId"); return value != null && !String.valueOf(value).isBlank(); }
    private String errorMessage(Throwable error) { Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause(); String message = cause.getMessage(); return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message; }
    private boolean asksForTools(String content) { String value = content == null ? "" : content; return value.contains("哪些工具") || value.contains("什么工具") || value.contains("工具列表") || value.contains("能调用") || value.contains("可用工具"); }
    private String toolSummary(AiChatbotGroupPolicy policy) {
        List<String> enabled = policy.enabledToolNames();
        if (enabled.isEmpty()) return "本群当前未启用 AI 工具。管理员可在 AI 群聊机器人配置中开启。";
        List<String> lines = context.framework().ai().tools().stream().filter(tool -> enabled.contains(tool.name())).map(tool -> "- " + tool.title() + "：" + tool.description()).toList();
        return lines.isEmpty() ? "本群配置的工具当前未注册或对应插件未启用。" : "本群已启用工具：\n" + String.join("\n", lines);
    }
    private String prompt(String mode, AiChatbotGroupPolicy policy) {
        return policy.systemPrompt() + (policy.persona().isBlank() ? "" : " 人设：" + policy.persona())
                + ("RANDOM".equals(mode) ? " 这是随机回复，不调用工具，不要打断正常交流。" : " 这是用户明确 @ 你，请优先回答当前用户的问题。");
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String groupId(PluginEvent event) { return event.connectionId() + ":" + event.channelId(); }
    private String memoryId(PluginEvent event, Long userId) { return groupId(event) + ":" + userId; }
    private void reply(PluginEvent event, String text) { context.framework().messaging().send(new PluginMessageRequest(event.connectionId(), event.platform(), event.selfId(), event.channelId(), new PluginMessageContent(PluginMessageContent.Type.TEXT, text, null, event.messageId() == null ? Map.of() : Map.of("message_id", event.messageId())))); }
}
