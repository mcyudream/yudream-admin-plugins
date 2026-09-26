package online.yudream.base.plugin.playtimepoints.infrastructure.repository;

import online.yudream.base.plugin.playtimepoints.domain.aggregate.PointsSettlement;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.SettlementState;
import online.yudream.base.plugin.playtimepoints.domain.repo.PlaytimePointsRepository;
import online.yudream.base.plugin.playtimepoints.domain.valobj.PlaytimePointsSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 测试替身：内存版仓储，语义与文档仓储一致（列表按 windowEnd 倒序分页）。 */
public class FakePlaytimePointsRepository implements PlaytimePointsRepository {

    private PlaytimePointsSettings settings;
    private final Map<String, SettlementState> states = new LinkedHashMap<>();
    private final Map<String, PointsSettlement> settlements = new LinkedHashMap<>();

    @Override
    public Optional<PlaytimePointsSettings> findSettings() {
        return Optional.ofNullable(settings);
    }

    @Override
    public void saveSettings(PlaytimePointsSettings settings) {
        this.settings = settings;
    }

    @Override
    public Optional<SettlementState> findState(String stateId) {
        return Optional.ofNullable(states.get(stateId));
    }

    @Override
    public void saveState(SettlementState state) {
        states.put(state.id(), state);
    }

    @Override
    public void saveSettlement(PointsSettlement settlement) {
        settlements.put(settlement.id(), settlement);
    }

    @Override
    public SettlementPage settlements(String serverId, String userId, String keyword, int page, int size) {
        List<PointsSettlement> matched = new ArrayList<>(settlements.values()).stream()
                .filter(item -> serverId == null || serverId.equals(item.serverId()))
                .filter(item -> userId == null || userId.equals(item.userId()))
                .filter(item -> keyword == null
                        || (item.playerName() != null && item.playerName().toLowerCase().contains(keyword.toLowerCase())))
                .sorted(Comparator.comparingLong(PointsSettlement::windowEnd).reversed())
                .toList();
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        int from = Math.min((safePage - 1) * safeSize, matched.size());
        int to = Math.min(from + safeSize, matched.size());
        return new SettlementPage(matched.subList(from, to), matched.size());
    }

    public SettlementState state(String stateId) {
        return states.get(stateId);
    }
}
