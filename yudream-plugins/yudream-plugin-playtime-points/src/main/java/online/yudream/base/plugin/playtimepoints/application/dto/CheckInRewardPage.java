package online.yudream.base.plugin.playtimepoints.application.dto;

import java.util.List;

/** 打卡积分发放流水的分页结果。 */
public record CheckInRewardPage(List<CheckInRewardView> records, long total) {
}
