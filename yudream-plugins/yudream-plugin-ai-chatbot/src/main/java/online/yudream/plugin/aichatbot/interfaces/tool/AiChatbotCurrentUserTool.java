package online.yudream.plugin.aichatbot.interfaces.tool;

import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.ai.PluginAiTool;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolCall;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolDescriptor;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolResult;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolRisk;
import online.yudream.base.plugin.spi.system.user.PluginUserService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class AiChatbotCurrentUserTool implements PluginAiTool {
    public static final String NAME = "ai_chatbot.lookup_current_user";
    private final PluginUserService users;
    public AiChatbotCurrentUserTool(PluginUserService users) { this.users = users; }
    @Override public PluginAiToolDescriptor descriptor() { return new PluginAiToolDescriptor(NAME, "查询当前 QQ 账号", "查询当前发言 QQ 绑定的系统账号和角色", "plugin:ai-chatbot:use", PluginAiToolRisk.READ, false, Set.of("MENTION", "RANDOM"), Map.of()); }
    @Override public PluginAiToolResult execute(PluginAiExecutionContext context, PluginAiToolCall call) {
        if (context == null || context.platformUserId() == null || context.platformUserId().isBlank()) return new PluginAiToolResult("lookup", "当前消息没有可查询的 QQ", Map.of());
        return users.findByQq(context.platformUserId()).map(profile -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", String.valueOf(profile.id())); payload.put("qq", context.platformUserId()); payload.put("username", profile.username()); payload.put("nickname", profile.nickname()); payload.put("avatar", profile.avatar()); payload.put("status", profile.status());
            payload.put("roles", users.listRoles(profile.id()).stream().map(role -> Map.of("code", role.code(), "name", role.name())).toList());
            return new PluginAiToolResult("lookup", "已查询当前 QQ 绑定账号", payload);
        }).orElseGet(() -> new PluginAiToolResult("lookup", "当前 QQ 尚未绑定系统账号", Map.of("qq", context.platformUserId())));
    }
}
