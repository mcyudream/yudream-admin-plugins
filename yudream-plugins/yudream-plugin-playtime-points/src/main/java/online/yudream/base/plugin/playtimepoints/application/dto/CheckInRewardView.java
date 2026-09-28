package online.yudream.base.plugin.playtimepoints.application.dto;

/**
 * 打卡积分发放流水视图（REST 输出）。金额为十进制字符串；ID 一律字符串。
 *
 * <p>{@code source} 为 {@code PULL}（定时拉取）或 {@code REALTIME}（验收通过实时回调）。</p>
 *
 * <p>折算审计字段：{@code mode} 为 {@code FIXED}（每次固定积分）或 {@code HOURLY}（按时薪折算）；
 * {@code effectiveMillis} 是本次依据的有效在线毫秒数；{@code rate} 是本次实际生效的费率
 * （固定模式为每次金额，按时薪模式为每小时积分，非时长打卡回退为那笔全局固定积分）；
 * {@code note} 在按时薪/固定金额正常发放时为空，「非时长打卡（图片/文件/定位）按固定积分发放」时是这句
 * 中文语义说明，跳过发放（没有有效在线时长且固定积分为 0、折算不足最小单位）时是中文原因。</p>
 *
 * <p>前端区分两类 {@code HOURLY} 记录：{@code effectiveMillis <= 0} 且 {@code credit > 0} 的是
 * 「非时长打卡 · 固定 N 积分」（{@code N = credit}），其余按「有效在线 X × 时薪 rate = credit 积分」展示。</p>
 */
public record CheckInRewardView(
        String checkInId,
        String detailId,
        String detailTitle,
        String projectId,
        String projectName,
        String userId,
        String points,
        String credit,
        String source,
        long acceptedAt,
        long createdAt,
        String mode,
        long effectiveMillis,
        String rate,
        String note) {
}
