package online.yudream.base.plugin.mcguess.domain;

/** 插件设置存储：当前仅游戏数据版本（空 = 跟随 mc-wiki 默认发布版本）。 */
public interface McguessSettingsRepository {

    /** 钉住的数据版本；未设置或空串表示跟随 mc-wiki 默认发布版本。 */
    String gameVersion();

    /** 保存钉住版本；空串清除设置（恢复跟随默认）。 */
    void saveGameVersion(String version);
}
