package online.yudream.base.plugin.playtimepoints.domain.aggregate;

import java.util.Locale;

/**
 * 打卡积分发放流水：一条「项目打卡验收通过」的积分发放记录。
 *
 * <p>id 就是 {@code checkInId}，因此「这条打卡是否已发放」可以用一次按 id 的点查判断——这是本插件侧的
 * 幂等保险；钱包侧的 {@code businessNo} 是第二重保险（钱包按单号幂等，重复入账不会发生）。</p>
 *
 * <p>金额为十进制字符串（与配置同精度），{@code points} 是配置费率算得的积分、{@code credit} 是实际入账
 * 金额；固定模式是整笔发放，因此二者相同；按时薪模式按货币精度取整后二者也相同（都是取整后的值）。
 * 按时薪模式下「没有时长的打卡」（图片/文件/定位等非 MC 打卡）改按全局固定积分发放时，{@code points} 与
 * {@code credit} 同样相同，但 {@code rate} 记的是**本次实际生效的固定积分**（时薪没有参与这次计算），
 * {@code effectiveMillis} 为 0，{@code note} 说明语义。不可发放（没有有效在线时长且固定积分为 0、
 * 或折算不足一个最小单位）时也会留下一条流水，此时 {@code credit = "0"}、{@code note} 记录中文原因，
 * 便于排查；游标照常越过。</p>
 *
 * @param checkInId   打卡记录 id（幂等键，同时作为流水 id）
 * @param source      发放来源：{@value #SOURCE_PULL} 定时拉取 / {@value #SOURCE_REALTIME} 验收通过实时回调
 * @param acceptedAt  该工作细节最后一次验收通过的时间（毫秒）
 * @param createdAt   本流水落库时间（毫秒）
 * @param mode        本次生效的计算方式：{@value #MODE_FIXED} 每次固定积分 / {@value #MODE_HOURLY} 按时薪折算
 *                    （非时长打卡的固定积分回退也记 {@value #MODE_HOURLY}，用 {@code effectiveMillis = 0}
 *                    + 非空 {@code note} 与「按时薪折算出来的」记录区分）
 * @param effectiveMillis 本次依据的有效在线毫秒数（来自打卡证据的窗口值）；非 MC 打卡或旧数据为 0
 * @param rate        本次实际生效的费率：固定模式是每次金额，按时薪模式是每小时积分，
 *                    非时长打卡回退是那笔全局固定积分（十进制字符串）
 * @param note        说明：正常按时薪/固定金额发放时为空；非时长打卡按固定积分发放时是中文语义说明；
 *                    跳过发放时是中文原因（无时长且固定积分为 0 / 折算为 0）
 */
public record CheckInReward(
        String checkInId,
        String detailId,
        String detailTitle,
        String projectId,
        String projectName,
        String userId,
        String points,
        String credit,
        String businessNo,
        String source,
        long acceptedAt,
        long createdAt,
        String mode,
        long effectiveMillis,
        String rate,
        String note) {

    /** 定时拉取（增量游标）发放。 */
    public static final String SOURCE_PULL = "PULL";
    /** 验收通过实时回调发放。 */
    public static final String SOURCE_REALTIME = "REALTIME";
    /** 计算方式：每次固定积分（与升级前一致）。 */
    public static final String MODE_FIXED = "FIXED";
    /** 计算方式：按时薪折算。 */
    public static final String MODE_HOURLY = "HOURLY";

    public CheckInReward {
        detailId = text(detailId);
        detailTitle = text(detailTitle);
        projectId = text(projectId);
        projectName = text(projectName);
        points = text(points);
        credit = text(credit);
        businessNo = text(businessNo);
        source = text(source);
        mode = MODE_HOURLY.equals(text(mode).toUpperCase(Locale.ROOT)) ? MODE_HOURLY : MODE_FIXED;
        effectiveMillis = Math.max(effectiveMillis, 0L);
        // 旧流水没有 rate 键：固定模式下费率就是当时的配置金额，用 points 兜底即可。
        rate = text(rate).isEmpty() ? points : text(rate);
        note = text(note);
    }

    /**
     * 兼容新增审计字段之前的十二参调用：等价于「固定金额发放」，行为与升级前完全一致。
     * 新增字段一律取默认值（费率 = points、时长 0、无说明）。
     */
    public CheckInReward(String checkInId, String detailId, String detailTitle, String projectId, String projectName,
                         String userId, String points, String credit, String businessNo, String source,
                         long acceptedAt, long createdAt) {
        this(checkInId, detailId, detailTitle, projectId, projectName, userId, points, credit, businessNo, source,
                acceptedAt, createdAt, MODE_FIXED, 0L, points, "");
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
