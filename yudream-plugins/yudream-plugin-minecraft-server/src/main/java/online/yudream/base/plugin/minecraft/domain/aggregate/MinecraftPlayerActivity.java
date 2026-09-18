package online.yudream.base.plugin.minecraft.domain.aggregate;

import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftSubServerActivity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 一个玩家在一台 Admin 服务器条目（群组服）上的活动记录。
 *
 * <p>记录仍然以 (serverId, playerId) 为主键，没有改索引；子服维度是它内部新增的拆分：
 * {@link #subServers} 按子服名分别累计，而 {@link #totalOnlineMillis}、{@link #totalAfkMillis}、
 * {@code currentOnlineSince}、{@code currentAfkSince}、{@code lastJoinedAt}、{@code lastQuitAt}
 * 永远是这些子服的<b>汇总</b>（求和 / 任意一个 / 最早 / 最晚），由构造器统一推导，
 * 因此既有的查询、DTO 与跨插件 API 都不需要改动，也不可能与拆分结果漂移。
 *
 * <p>旧签名 {@code join(name, at)}、{@code quit(name, at)}、{@code startAfk}、{@code endAfk}
 * 依然可用，等价于对默认桶 {@link #DEFAULT_SUB_SERVER} 操作，现有调用方与测试无需改动。
 */
public record MinecraftPlayerActivity(
        String id,
        String serverId,
        String playerId,
        String playerName,
        long totalOnlineMillis,
        long totalAfkMillis,
        Long currentOnlineSince,
        Long currentAfkSince,
        Long lastJoinedAt,
        Long lastQuitAt,
        long createdAt,
        long updatedAt,
        Map<String, MinecraftSubServerActivity> subServers
) {

    /** 上报体里没有子服维度时使用的默认桶名，与值对象里的常量保持一致。 */
    public static final String DEFAULT_SUB_SERVER = MinecraftSubServerActivity.DEFAULT_NAME;

    public MinecraftPlayerActivity {
        serverId = requireText(serverId, "服务器 ID 不能为空");
        playerId = requireText(playerId, "玩家 ID 不能为空");
        id = id == null || id.isBlank() ? id(serverId, playerId) : id.trim();
        playerName = normalizeName(playerName, playerId);
        subServers = normalizeSubServers(subServers);
        if (subServers.isEmpty()) {
            // 本次改动之前写入的文档只有跨服汇总字段，没有 subServers：用汇总值补一个默认桶，
            // 这样旧数据读出来仍然可用，也不会因为“汇总为空”而丢失历史时长。
            MinecraftSubServerActivity legacy = legacyBucket(totalOnlineMillis, totalAfkMillis,
                    currentOnlineSince, currentAfkSince, lastJoinedAt, lastQuitAt);
            if (legacy != null) {
                Map<String, MinecraftSubServerActivity> seeded = new LinkedHashMap<>();
                seeded.put(legacy.name(), legacy);
                subServers = Collections.unmodifiableMap(seeded);
            }
        }
        totalOnlineMillis = totalOnline(subServers);
        totalAfkMillis = totalAfk(subServers);
        currentOnlineSince = earliest(subServers, MinecraftSubServerActivity::currentOnlineSince);
        currentAfkSince = currentOnlineSince == null
                ? null
                : earliest(subServers, MinecraftSubServerActivity::currentAfkSince);
        lastJoinedAt = latest(subServers, MinecraftSubServerActivity::lastJoinedAt);
        lastQuitAt = latest(subServers, MinecraftSubServerActivity::lastQuitAt);
        createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        updatedAt = updatedAt <= 0 ? createdAt : updatedAt;
    }

    public static MinecraftPlayerActivity empty(String serverId, String playerId, String playerName, long eventAt) {
        return new MinecraftPlayerActivity(null, serverId, playerId, playerName, 0, 0,
                null, null, null, null, eventAt, eventAt, Map.of());
    }

    // ------------------------------------------------------------------ 子服维度

    public MinecraftPlayerActivity join(String nextPlayerName, long eventAt) {
        return join(DEFAULT_SUB_SERVER, nextPlayerName, eventAt);
    }

    /** 在指定子服上上线；已经在线的子服不会被重置，换服则是“旧子服下线 + 新子服上线”。 */
    public MinecraftPlayerActivity join(String subServer, String nextPlayerName, long eventAt) {
        return edit(subServer, nextPlayerName, eventAt, bucket -> bucket.join(eventAt));
    }

    public MinecraftPlayerActivity quit(String nextPlayerName, long eventAt) {
        return quit(DEFAULT_SUB_SERVER, nextPlayerName, eventAt);
    }

    /** 指定子服下线，只结算该子服；其它子服上的在线状态不受影响。 */
    public MinecraftPlayerActivity quit(String subServer, String nextPlayerName, long eventAt) {
        return edit(subServer, nextPlayerName, eventAt, bucket -> bucket.quit(eventAt));
    }

    public MinecraftPlayerActivity startAfk(String nextPlayerName, long eventAt) {
        return startAfk(DEFAULT_SUB_SERVER, nextPlayerName, eventAt);
    }

    public MinecraftPlayerActivity startAfk(String subServer, String nextPlayerName, long eventAt) {
        return edit(subServer, nextPlayerName, eventAt, bucket -> bucket.startAfk(eventAt));
    }

    public MinecraftPlayerActivity endAfk(String nextPlayerName, long eventAt) {
        return endAfk(DEFAULT_SUB_SERVER, nextPlayerName, eventAt);
    }

    public MinecraftPlayerActivity endAfk(String subServer, String nextPlayerName, long eventAt) {
        return edit(subServer, nextPlayerName, eventAt, bucket -> bucket.endAfk(eventAt));
    }

    /**
     * 关闭所有仍在线的子服，用于整服离线恢复与整服快照兜底。
     *
     * <p>与 {@link #quit(String, long)} 的区别：那个只关默认桶，这个会把每个子服都结算掉，
     * 否则一个同时在 fabric 与 paper 上的玩家在整服离线后仍然是“在线”。
     */
    public MinecraftPlayerActivity quitAll(String nextPlayerName, long eventAt) {
        Map<String, MinecraftSubServerActivity> buckets = buckets();
        for (Map.Entry<String, MinecraftSubServerActivity> entry : buckets.entrySet()) {
            if (entry.getValue().online()) {
                entry.setValue(entry.getValue().quit(eventAt));
            }
        }
        return withBuckets(buckets, nextPlayerName, eventAt);
    }

    /**
     * 关闭指定子服，但只在它确实在线时结算。
     *
     * @return 结算后的记录；该子服不在线时返回原记录
     */
    public MinecraftPlayerActivity closeSubServer(String subServer, long eventAt) {
        MinecraftSubServerActivity bucket = subServers.get(MinecraftSubServerActivity.normalizeName(subServer));
        if (bucket == null || !bucket.online()) {
            return this;
        }
        return quit(subServer, playerName, eventAt);
    }

    // ------------------------------------------------------------------ 汇总视图

    /** 只要任意一个子服上在线即为在线。 */
    public boolean online() {
        return subServers.values().stream().anyMatch(MinecraftSubServerActivity::online);
    }

    public boolean afk() {
        return subServers.values().stream().anyMatch(MinecraftSubServerActivity::afk);
    }

    /** 含未结算区间的跨子服在线合计。 */
    public long totalOnlineMillisAt(long now) {
        return subServers.values().stream().mapToLong(bucket -> bucket.onlineAt(now)).sum();
    }

    /** 含未结算区间的跨子服挂机合计。 */
    public long totalAfkMillisAt(long now) {
        return subServers.values().stream().mapToLong(bucket -> bucket.afkAt(now)).sum();
    }

    public static String id(String serverId, String playerId) {
        return requireText(serverId, "服务器 ID 不能为空") + ":" + requireText(playerId, "玩家 ID 不能为空");
    }

    // ------------------------------------------------------------------ 内部

    private MinecraftPlayerActivity edit(String subServer, String nextPlayerName, long eventAt,
                                         Function<MinecraftSubServerActivity, MinecraftSubServerActivity> change) {
        String key = MinecraftSubServerActivity.normalizeName(subServer);
        Map<String, MinecraftSubServerActivity> buckets = buckets();
        buckets.put(key, change.apply(buckets.getOrDefault(key, MinecraftSubServerActivity.empty(key))));
        return withBuckets(buckets, nextPlayerName, eventAt);
    }

    private Map<String, MinecraftSubServerActivity> buckets() {
        return new LinkedHashMap<>(subServers);
    }

    /** 顶层汇总字段由构造器重新推导，这里只需要交出新的子服表。 */
    private MinecraftPlayerActivity withBuckets(Map<String, MinecraftSubServerActivity> buckets,
                                                String nextPlayerName, long eventAt) {
        return new MinecraftPlayerActivity(id, serverId, playerId, nameOrCurrent(nextPlayerName),
                totalOnlineMillis, totalAfkMillis, currentOnlineSince, currentAfkSince,
                lastJoinedAt, lastQuitAt, createdAt, eventAt, buckets);
    }

    private String nameOrCurrent(String nextPlayerName) {
        return nextPlayerName == null || nextPlayerName.isBlank() ? playerName : nextPlayerName;
    }

    private static Map<String, MinecraftSubServerActivity> normalizeSubServers(
            Map<String, MinecraftSubServerActivity> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, MinecraftSubServerActivity> result = new LinkedHashMap<>();
        for (MinecraftSubServerActivity bucket : values.values()) {
            if (bucket != null) {
                result.put(bucket.name(), bucket);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** 汇总字段全为空说明这是本次改动之前就有的空记录，不需要补默认桶。 */
    private static MinecraftSubServerActivity legacyBucket(long onlineMillis, long afkMillis,
                                                           Long currentOnlineSince, Long currentAfkSince,
                                                           Long lastJoinedAt, Long lastQuitAt) {
        boolean blank = onlineMillis <= 0 && afkMillis <= 0 && currentOnlineSince == null
                && currentAfkSince == null && lastJoinedAt == null && lastQuitAt == null;
        if (blank) {
            return null;
        }
        return new MinecraftSubServerActivity(DEFAULT_SUB_SERVER, onlineMillis, afkMillis,
                currentOnlineSince, currentAfkSince, lastJoinedAt, lastQuitAt);
    }

    private static long totalOnline(Map<String, MinecraftSubServerActivity> buckets) {
        return buckets.values().stream().mapToLong(MinecraftSubServerActivity::onlineMillis).sum();
    }

    private static long totalAfk(Map<String, MinecraftSubServerActivity> buckets) {
        return buckets.values().stream().mapToLong(MinecraftSubServerActivity::afkMillis).sum();
    }

    private static Long earliest(Map<String, MinecraftSubServerActivity> buckets,
                                 Function<MinecraftSubServerActivity, Long> field) {
        Long result = null;
        for (MinecraftSubServerActivity bucket : buckets.values()) {
            Long value = field.apply(bucket);
            if (value != null && (result == null || value < result)) {
                result = value;
            }
        }
        return result;
    }

    private static Long latest(Map<String, MinecraftSubServerActivity> buckets,
                               Function<MinecraftSubServerActivity, Long> field) {
        Long result = null;
        for (MinecraftSubServerActivity bucket : buckets.values()) {
            Long value = field.apply(bucket);
            if (value != null && (result == null || value > result)) {
                result = value;
            }
        }
        return result;
    }

    private static String normalizeName(String value, String fallback) {
        String text = value == null || value.isBlank() ? fallback : value.trim();
        return text.length() > 64 ? text.substring(0, 64) : text;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
