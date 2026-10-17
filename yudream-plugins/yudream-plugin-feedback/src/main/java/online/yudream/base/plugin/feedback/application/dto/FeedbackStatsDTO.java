package online.yudream.base.plugin.feedback.application.dto;

/**
 * 反馈统计。
 *
 * <p>口径与列表页一致：{@code open} = 待处理 + 处理中，供统计卡与快捷筛选共用；
 * {@code total} 是「全部」——不含 {@code archived}（归档单独成卡，不重复计入）；
 * {@code archived} 是终态「已归档」的条数。
 */
public record FeedbackStatsDTO(
        long total,
        long pending,
        long processing,
        long open,
        long resolved,
        long rejected,
        long archived
) {
}
