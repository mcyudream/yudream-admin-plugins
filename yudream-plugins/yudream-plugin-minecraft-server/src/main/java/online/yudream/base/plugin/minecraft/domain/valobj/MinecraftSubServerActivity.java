package online.yudream.base.plugin.minecraft.domain.valobj;

/**
 * 玩家在<b>某一个子服</b>上的在线与挂机累计。
 *
 * <p>一个 Velocity 群组服下玩家会在多个子服之间移动（{@code /server fabric}、{@code /server paper}……）。
 * 每个子服各自结算自己的时长，跨子服的合计由 {@link online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftPlayerActivity}
 * 汇总，因此玩家换服不再表现为一次退出加一次加入，换服前的时长也不会丢失。
 *
 * <p>本对象只承担原先 {@code MinecraftPlayerActivity} 里 {@code join/quit/startAfk/endAfk/duration}
 * 那部分逻辑，只是把结算范围收窄到一个子服；子服名不再只有玩家名一个字段。
 *
 * <p><b>没有子服维度的上报</b>（独立服、旧版桥接的扁平快照、事件体里不带 {@code server} 字段）
 * 一律归入 {@link #DEFAULT_NAME} 这一个默认桶，因此旧数据与旧调用方的语义完全不变。
 */
public record MinecraftSubServerActivity(
        String name,
        long onlineMillis,
        long afkMillis,
        Long currentOnlineSince,
        Long currentAfkSince,
        Long lastJoinedAt,
        Long lastQuitAt
) {

    /**
     * “没有子服维度”时使用的默认桶名。
     *
     * <p>上报体里缺省或空白的子服名都会归一到这里；这也是旧文档读入后唯一的桶名。
     */
    public static final String DEFAULT_NAME = "default";

    public MinecraftSubServerActivity {
        name = normalizeName(name);
        onlineMillis = Math.max(onlineMillis, 0);
        afkMillis = Math.max(afkMillis, 0);
        if (currentOnlineSince == null) {
            currentAfkSince = null;
        }
    }

    public static MinecraftSubServerActivity empty(String name) {
        return new MinecraftSubServerActivity(name, 0, 0, null, null, null, null);
    }

    /** 把缺省或空白的子服名统一成默认桶名。 */
    public static String normalizeName(String value) {
        return value == null || value.isBlank() ? DEFAULT_NAME : value.trim();
    }

    /** 是否就是默认桶（没有子服维度）。 */
    public boolean isDefaultBucket() {
        return DEFAULT_NAME.equals(name);
    }

    /** 上线：已经在线的重复加入不再重置起点，避免把时长算丢。 */
    public MinecraftSubServerActivity join(long eventAt) {
        if (currentOnlineSince != null) {
            return this;
        }
        return new MinecraftSubServerActivity(name, onlineMillis, afkMillis, eventAt, null, eventAt, lastQuitAt);
    }

    /** 下线：结算当前在线与挂机区间。重复下线不会再累计一次。 */
    public MinecraftSubServerActivity quit(long eventAt) {
        return new MinecraftSubServerActivity(name, onlineMillis + duration(currentOnlineSince, eventAt),
                afkMillis + duration(currentAfkSince, eventAt), null, null, lastJoinedAt, eventAt);
    }

    /** 开始挂机；尚未在线时先按上线处理。 */
    public MinecraftSubServerActivity startAfk(long eventAt) {
        MinecraftSubServerActivity online = currentOnlineSince == null ? join(eventAt) : this;
        if (online.currentAfkSince != null) {
            return online;
        }
        return new MinecraftSubServerActivity(online.name, online.onlineMillis, online.afkMillis,
                online.currentOnlineSince, eventAt, online.lastJoinedAt, online.lastQuitAt);
    }

    /** 结束挂机：结算挂机区间。 */
    public MinecraftSubServerActivity endAfk(long eventAt) {
        if (currentAfkSince == null) {
            return this;
        }
        return new MinecraftSubServerActivity(name, onlineMillis, afkMillis + duration(currentAfkSince, eventAt),
                currentOnlineSince, null, lastJoinedAt, lastQuitAt);
    }

    public boolean online() {
        return currentOnlineSince != null;
    }

    public boolean afk() {
        return currentAfkSince != null;
    }

    /** 含尚未结算的当前在线区间。 */
    public long onlineAt(long now) {
        return onlineMillis + duration(currentOnlineSince, now);
    }

    /** 含尚未结算的当前挂机区间。 */
    public long afkAt(long now) {
        return afkMillis + duration(currentAfkSince, now);
    }

    private static long duration(Long startAt, long endAt) {
        if (startAt == null || endAt <= startAt) {
            return 0;
        }
        return endAt - startAt;
    }
}
