package online.yudream.base.plugin.minecraft.api;

/**
 * mcpanel 面板回传的实例运行状态。
 *
 * <p>{@code state} 为面板实例状态（running / exited / stopped / created / installing / unknown / deleted）；
 * {@code updatedAt} 为面板发送时刻（epoch ms）。面板按实例状态变化推送，重启后重新上报。</p>
 */
public record PluginMinecraftPanelState(String instanceId, String state, long updatedAt) {
}
