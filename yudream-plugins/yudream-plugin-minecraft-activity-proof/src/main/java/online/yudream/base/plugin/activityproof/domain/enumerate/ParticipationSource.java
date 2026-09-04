package online.yudream.base.plugin.activityproof.domain.enumerate;

/** 参与记录来源：用户自行报名 / 管理员手动添加 / 服务器自动同步。 */
public enum ParticipationSource {
    SELF,
    MANUAL,
    AUTO;

    public static ParticipationSource of(String value) {
        if (value == null || value.isBlank()) {
            return SELF;
        }
        try {
            return ParticipationSource.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SELF;
        }
    }
}
