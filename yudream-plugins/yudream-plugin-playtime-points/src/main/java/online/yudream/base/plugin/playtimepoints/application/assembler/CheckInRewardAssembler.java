package online.yudream.base.plugin.playtimepoints.application.assembler;

import online.yudream.base.plugin.playtimepoints.application.dto.CheckInRewardView;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.CheckInReward;

/** 打卡积分发放流水 → REST 视图。 */
public final class CheckInRewardAssembler {

    private CheckInRewardAssembler() {
    }

    public static CheckInRewardView toView(CheckInReward reward) {
        return new CheckInRewardView(reward.checkInId(), reward.detailId(), reward.detailTitle(), reward.projectId(),
                reward.projectName(), reward.userId(), reward.points(), reward.credit(), reward.source(),
                reward.acceptedAt(), reward.createdAt(), reward.mode(), reward.effectiveMillis(), reward.rate(),
                reward.note());
    }
}
