package online.yudream.base.plugin.minecraft.application.cmd;

import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftSubServerActivity;

/**
 * 一次玩家事件上报。
 *
 * @param server 子服名（代理下的 {@code /server} 名）。缺省或空白表示没有子服维度，归入默认桶。
 */
public record MinecraftPlayerEventCmd(
        String playerId,
        String playerName,
        Long eventAt,
        String server
) {

    /** 兼容不带子服维度的旧调用方与旧上报端。 */
    public MinecraftPlayerEventCmd(String playerId, String playerName, Long eventAt) {
        this(playerId, playerName, eventAt, null);
    }

    /** 归一后的子服名：空白一律变成默认桶名。 */
    public String subServer() {
        return MinecraftSubServerActivity.normalizeName(server);
    }
}
