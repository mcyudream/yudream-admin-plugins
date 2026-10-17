package online.yudream.base.plugin.feedback.application.dto;

import java.util.List;

/**
 * 反馈视图。
 *
 * <p>与请求者相关的派生标记（{@code owned} / {@code canEdit} / {@code canArchive}）
 * 由服务层计算，前端不再根据作者 ID 自行推测权限：{@code canArchive} 只在该反馈已由管理员
 * 给出结论（已解决 / 不予处理）时为真，归档后为假。
 *
 * <p>{@code autoArchiveAt} 是自动归档时间（结论时间 + 宽限期），用户端据此提示还剩几天。
 *
 * <p>用户端响应中的 {@code comments} 与 {@code activities} 已过滤掉内部备注；
 * {@code authorName} 在用户端即当前登录者本人。
 *
 * <p>处理人是集合：{@code assigneeIds} 用于筛选与回显草稿，{@code assignees} 携带展示名。
 */
public record FeedbackDTO(
        String id,
        String title,
        String description,
        String type,
        String typeLabel,
        String severity,
        String severityLabel,
        String status,
        String statusLabel,
        boolean open,
        String authorId,
        String authorName,
        List<String> assigneeIds,
        List<FeedbackAssigneeDTO> assignees,
        List<String> attachments,
        List<FeedbackCommentDTO> comments,
        List<FeedbackActivityDTO> activities,
        int commentCount,
        long createdAt,
        String createdAtLabel,
        long updatedAt,
        String updatedAtLabel,
        Long closedAt,
        String closedAtLabel,
        boolean owned,
        boolean canEdit,
        boolean canArchive,
        Long autoArchiveAt,
        String autoArchiveAtLabel
) {
}
