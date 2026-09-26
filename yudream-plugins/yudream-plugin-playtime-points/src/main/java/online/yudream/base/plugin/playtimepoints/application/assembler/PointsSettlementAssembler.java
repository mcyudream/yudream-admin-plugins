package online.yudream.base.plugin.playtimepoints.application.assembler;

import online.yudream.base.plugin.playtimepoints.application.dto.SettlementView;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.PointsSettlement;
import online.yudream.base.plugin.playtimepoints.domain.service.PointsCalculator;

public final class PointsSettlementAssembler {

    private PointsSettlementAssembler() {
    }

    public static SettlementView toView(PointsSettlement settlement) {
        return new SettlementView(
                settlement.id(),
                settlement.serverId(),
                settlement.serverName(),
                settlement.playerId(),
                settlement.playerName(),
                settlement.userId(),
                settlement.windowEnd(),
                settlement.onlineMillis(),
                settlement.afkMillis(),
                settlement.effectiveMillis(),
                Math.max(0L, settlement.effectiveMillis()) / 60_000,
                settlement.weight(),
                PointsCalculator.plain(PointsCalculator.parseCarry(settlement.points())),
                PointsCalculator.plain(PointsCalculator.parseCarry(settlement.credited())),
                settlement.assetCode(),
                settlement.createdAt());
    }
}
