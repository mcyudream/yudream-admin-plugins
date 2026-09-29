package online.yudream.base.plugin.projectprogress.api;

/**
 * 项目的极简视图，供消费方做「按项目配置/展示」的最小选择器。
 *
 * @param id      项目 id
 * @param name    项目名称
 * @param enabled 项目是否启用（消费方通常只对启用中的项目发放积分，但要能看到被停用的项目）
 */
public record PluginProjectSummary(String id, String name, boolean enabled) {
}
