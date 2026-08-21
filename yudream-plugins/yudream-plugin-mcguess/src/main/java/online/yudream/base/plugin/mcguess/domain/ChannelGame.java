package online.yudream.base.plugin.mcguess.domain;

/**
 * 群回合制对局的公共契约：每种模式每个群各一局进行中，仓储按 channelKey 检索。
 */
public interface ChannelGame {

    String getId();

    String getConnectionId();

    String getPlatform();

    String getChannelId();

    String getChannelKey();

    String getStatus();

    boolean isPlaying();

    long getStartedAt();

    String getWinnerQq();

    String getWinnerUserId();

    Long getEndedAt();
}
