package online.yudream.base.plugin.launcher.api;

import java.util.Map;

/**
 * 数据信封中的动作声明（协议 v2 §4.2）。
 *
 * @param code       动作 code（provider 内唯一；remote 类型时即 {@code /v2/action/{providerCode}/{actionCode}} 的 actionCode）
 * @param title      按钮文案
 * @param icon       图标（i-ri:xxx / i-mdi:xxx），可空
 * @param kind       按钮风格：primary | secondary | danger，空按 secondary
 * @param type       动作类型：client:reload | client:copy | client:open-url | client:install-pack |
 *                   client:launch-server | remote | extension:*（未知类型启动器隐藏不渲染）
 * @param payload    动作参数；支持 {@code {item.xxx}} / {@code {items.xxx}} 模板占位，启动器通用求值
 * @param requiresPermission 执行该动作所需权限码；适配器按当前用户权限过滤后下发，空表示登录即可
 */
public record LauncherAction(
        String code,
        String title,
        String icon,
        String kind,
        String type,
        Map<String, Object> payload,
        String requiresPermission
) {
    public LauncherAction {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        requiresPermission = requiresPermission == null ? "" : requiresPermission.trim();
    }

    /**
     * 常用签名：无图标、无权限要求。
     */
    public LauncherAction(String code, String title, String kind, String type, Map<String, Object> payload) {
        this(code, title, "", kind, type, payload, "");
    }
}
