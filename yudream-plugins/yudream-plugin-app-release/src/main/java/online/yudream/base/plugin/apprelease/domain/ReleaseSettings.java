package online.yudream.base.plugin.apprelease.domain;

/** 发布通道设置：低于 minVersionCode 的客户端强制更新（0 = 不启用强制线）。 */
public record ReleaseSettings(int minVersionCode, long updatedAt) {

    public ReleaseSettings {
        if (minVersionCode < 0) {
            throw new IllegalArgumentException("minVersionCode 不能为负数");
        }
    }

    public static ReleaseSettings defaults() {
        return new ReleaseSettings(0, 0L);
    }
}
