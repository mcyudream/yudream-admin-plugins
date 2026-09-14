package online.yudream.base.plugin.authlib.api;

/**
 * 角色（ygg profile）的对外投影。仅暴露启动器选择角色所需的字段。
 *
 * @param id    ygg 角色 UUID（无连字符）
 * @param name  ygg 角色名（启动器以本字段作为显示名）
 */
public record PluginAuthProfile(String id, String name) {
}