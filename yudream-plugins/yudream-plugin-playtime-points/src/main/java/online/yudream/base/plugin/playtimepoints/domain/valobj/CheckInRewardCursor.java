package online.yudream.base.plugin.playtimepoints.domain.valobj;

/**
 * 打卡积分发放游标：记录「已经处理到哪一条验收通过时间」。
 *
 * <p>{@code lastAcceptedAt} 是**含下界**的增量游标，语义与 project-progress 的
 * {@code acceptedCheckIns(sinceAcceptedAt, page, size)} 一致：下一轮从
 * {@code acceptedAt >= lastAcceptedAt} 开始拉，边界那一条会重复出现，靠发放流水幂等跳过。</p>
 *
 * <p>游标**只推进到已成功处理的位置**：发放失败（未绑定用户、钱包不可用、货币未启用、入账异常）时游标停在
 * 失败记录之前，下一轮从该位置重试，失败记录不会被跳过。</p>
 *
 * @param lastAcceptedAt   已成功处理到的验收通过时间（含）；0 表示从头开始
 * @param deliveredCount   累计新发放条数，仅用于观测（判重靠发放流水，不靠这个计数）
 * @param updatedAt        最后更新时间（毫秒）
 */
public record CheckInRewardCursor(long lastAcceptedAt, long deliveredCount, long updatedAt) {

    public static CheckInRewardCursor empty() {
        return new CheckInRewardCursor(0L, 0L, 0L);
    }

    /** 推进游标：只增不减，时间倒退的输入被忽略。 */
    public CheckInRewardCursor advancedTo(long acceptedAt, long delivered, long now) {
        return new CheckInRewardCursor(Math.max(lastAcceptedAt, acceptedAt),
                Math.max(deliveredCount, delivered), now);
    }
}
