package online.yudream.base.plugin.playtimepoints.domain.repo;

import online.yudream.base.plugin.playtimepoints.domain.aggregate.PointsSettlement;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.SettlementState;
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
}
