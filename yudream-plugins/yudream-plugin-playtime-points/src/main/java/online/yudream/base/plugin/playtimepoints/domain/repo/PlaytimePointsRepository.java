package online.yudream.base.plugin.playtimepoints.domain.repo;

import online.yudream.base.plugin.playtimepoints.domain.aggregate.CheckInReward;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.PointsSettlement;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.SettlementState;
import online.yudream.base.plugin.playtimepoints.domain.valobj.CheckInRewardCursor;
import online.yudream.base.plugin.playtimepoints.domain.valobj.PlaytimePointsSettings;

import java.util.List;
import java.util.Optional;

public interface PlaytimePointsRepository {

    /* ---------- 设置（单例） ---------- */

    Optional<PlaytimePointsSettings> findSettings();

    void saveSettings(PlaytimePointsSettings settings);

    /* ---------- 结算基线 ---------- */

    Optional<SettlementState> findState(String stateId);

    void saveState(SettlementState state);

    /* ---------- 结算流水 ---------- */

    void saveSettlement(PointsSettlement settlement);

    /**
     * 分页列出结算流水（按结算时间倒序）。serverId/userId/keyword 均可空；keyword 按玩家名模糊匹配。
     * 上限受仓储扫描窗口约束，超大批量时 total 以窗口内为准。
     */
    SettlementPage settlements(String serverId, String userId, String keyword, int page, int size);

    record SettlementPage(List<PointsSettlement> records, long total) {
    }

    /* ---------- 打卡积分发放（project-progress 联动） ---------- */

    /** 增量游标；从未扫描过时为空（等价于 {@link CheckInRewardCursor#empty()}）。 */
    Optional<CheckInRewardCursor> findCheckInRewardCursor();

    void saveCheckInRewardCursor(CheckInRewardCursor cursor);

    /** 按打卡记录 id 查发放流水（存在即已发放）；这是本插件侧的幂等判据。 */
    Optional<CheckInReward> findCheckInReward(String checkInId);

    /** 落一条发放流水，id 为 checkInId。 */
    void saveCheckInReward(CheckInReward reward);

    /**
     * 分页列出打卡积分发放流水（按验收通过时间倒序、同时间按打卡记录 id 倒序）。
     * projectId/userId 均可空（空表示不按该维度过滤）。
     */
    CheckInRewardPage checkInRewards(String projectId, String userId, int page, int size);

    /** 某用户的全部打卡积分流水（按时间倒序，不分页），供用户端汇总合计与最近记录。 */
    List<CheckInReward> allCheckInRewards(String userId);

    record CheckInRewardPage(List<CheckInReward> records, long total) {
    }
}
