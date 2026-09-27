package online.yudream.base.plugin.minecraft.application.service;

/**
 * 群服互联事件出口：应用服务在玩家事件落库后回调，桥接服务负责格式化并投递到群聊。
 *
 * <p>监听器未设置时（如部分单测）不回调；桥接服务内部的任何失败都不允许影响上报主流程。
 */
public interface MinecraftBridgeListener {

    /** 玩家进出服：{@code join=true} 进服，否则退服。 */
    void onPresence(String serverId, boolean join, String playerId, String playerName, long eventAt);

    /** 游戏内事件：聊天、死亡、成就。 */
    void onGameEvent(String serverId, GameEventKind kind, String playerName, String content, long eventAt);

    /** 面板实例电源状态迁移（running↔停止）：启停通报进群。默认空实现兼容既有实现类。 */
    default void onServerPowerState(String serverId, boolean started, long atMs) {
    }

    enum GameEventKind {
        CHAT, DEATH, ADVANCEMENT
    }
}
