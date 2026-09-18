package online.yudream.base.plugin.minecraft.api;

/**
 * 一台下游子服，来自代理端桥接上报的拓扑。
 *
 * <p>只有群组服的代理条目才会有子服：单机服（独立 Fabric / Bukkit）没有子服维度，返回空列表。
 * 消费方据此决定要不要给操作者显示子服选择。
 *
 * <p>{@code sensor} 表示该子服上是否已装并确认了传感器；{@code defaultServer} 是「新玩家落在
 * 哪台」的标记，仅用于把默认入口排在前面或标注出来，不代表上报范围。
 */
public record PluginMinecraftSubServer(String name, String address, int online, boolean sensor,
                                       boolean defaultServer, int sort) {
}
