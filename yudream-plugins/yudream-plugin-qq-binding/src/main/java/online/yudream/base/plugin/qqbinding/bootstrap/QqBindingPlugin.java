package online.yudream.base.plugin.qqbinding.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginCommand;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.command.PluginCommandContext;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;

import java.util.Map;

@PluginSpec(code = QqBindingPlugin.CODE, name = "QQ群 QQ 绑定", version = "1.0.0", description = "通过群聊一次性绑定码绑定系统 QQ")
@PluginPermission(code = QqBindingPlugin.MANAGE_PERMISSION, name = "管理 QQ 绑定", module = "平台插件", description = "生成 QQ 绑定码")
public class QqBindingPlugin implements YuDreamPlugin {
    public static final String CODE = "qq-binding";
    public static final String MANAGE_PERMISSION = "plugin:qq-binding:manage";

    @Override
    public void onEnable(PluginContext context) {
        // @PluginHttpEndpoint methods on this entry class are registered by the host annotation scanner.
    }

    @PluginHttpEndpoint(method = "POST", path = "/binding-codes", permission = MANAGE_PERMISSION)
    public PluginHttpResponse issueCode(PluginHttpRequest request, PluginContext context) {
        Long userId = request.principal().userId();
        var code = context.framework().qqBindings().issue(userId);
        return PluginHttpResponse.ok(Map.of("code", code.code(), "expiresAt", code.expiresAt().toString()));
    }

    @PluginCommand(code = "qq-binding.bind", command = "绑定", name = "绑定系统 QQ", description = "使用后台生成的六位绑定码绑定本 QQ", allowAnonymous = true)
    public void bind(PluginCommandContext command, PluginContext context) {
        if (command.arguments().size() != 1 || command.event().userId() == null || command.event().channelId() == null) {
            reply(command, context, "用法：/绑定 六位绑定码");
            return;
        }
        try {
            Long userId = context.framework().qqBindings().consume(command.arguments().getFirst());
            context.framework().users().bindQqOnce(userId, command.event().userId());
            reply(command, context, "QQ 绑定成功。");
        } catch (RuntimeException exception) {
            reply(command, context, exception.getMessage() == null ? "绑定失败" : exception.getMessage());
        }
    }

    @PluginCommand(code = "qq-binding.profile", command = "我的账号", name = "查询绑定账号", description = "查询当前 QQ 绑定的系统账号")
    public void profile(PluginCommandContext command, PluginContext context) {
        if (command.userId() == null) {
            reply(command, context, "当前 QQ 尚未绑定系统账号。");
            return;
        }
        var profile = context.framework().users().findById(command.userId()).orElse(null);
        if (profile == null) {
            reply(command, context, "系统账号不存在或已不可用。");
            return;
        }
        reply(command, context, "已绑定账号：" + profile.username() + "（ID: " + profile.id() + "）");
    }

    private void reply(PluginCommandContext command, PluginContext context, String text) {
        context.framework().messaging().send(new PluginMessageRequest(
                command.event().connectionId(), command.event().platform(), command.event().selfId(), command.event().channelId(),
                new PluginMessageContent(PluginMessageContent.Type.TEXT, text, null, replyReferrer(command))
        ));
    }

    private Map<String, Object> replyReferrer(PluginCommandContext command) {
        String messageId = command.event().messageId();
        return messageId == null || messageId.isBlank() ? Map.of() : Map.of("message_id", messageId);
    }
}
