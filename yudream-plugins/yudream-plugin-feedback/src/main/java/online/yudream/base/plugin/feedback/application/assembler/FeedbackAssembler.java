package online.yudream.base.plugin.feedback.application.assembler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import online.yudream.base.plugin.feedback.application.dto.FeedbackActivityDTO;
import online.yudream.base.plugin.feedback.application.dto.FeedbackAssigneeDTO;
import online.yudream.base.plugin.feedback.application.dto.FeedbackCommentDTO;
import online.yudream.base.plugin.feedback.application.dto.FeedbackDTO;
import online.yudream.base.plugin.feedback.domain.aggregate.Feedback;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackActivityAction;
import online.yudream.base.plugin.feedback.domain.valobj.FeedbackActivity;
import online.yudream.base.plugin.feedback.domain.valobj.FeedbackComment;
import online.yudream.base.plugin.feedback.infrastructure.TimeSupport;

/**
 * 领域聚合到应用层 DTO 的转换。
 *
 * <p>{@code viewerAdmin} 决定是否下发内部备注（评论与留痕）：用户端必须看不到内部备注，
 * 否则「仅管理员可见」形同虚设；{@code viewerId} 决定归属与可编辑标记。
 */
public class FeedbackAssembler {

    public FeedbackDTO toDTO(Feedback feedback, Function<String, String> nameLoader,
                             String viewerId, boolean viewerAdmin) {
        boolean owned = feedback.ownedBy(viewerId);
        List<FeedbackCommentDTO> comments = new ArrayList<>();
        for (FeedbackComment comment : feedback.comments()) {
            if (comment.internal() && !viewerAdmin) {
                continue;
            }
            comments.add(new FeedbackCommentDTO(
                    comment.id(),
                    comment.authorId(),
                    nameOf(nameLoader, comment.authorId()),
                    comment.authorRole().code(),
                    comment.adminReply(),
                    comment.content(),
                    comment.internal(),
                    comment.createdAt(),
                    TimeSupport.formatDateTime(comment.createdAt())
            ));
        }
        List<FeedbackActivityDTO> activities = new ArrayList<>();
        for (FeedbackActivity activity : feedback.activities()) {
            // 内部备注属于管理员之间的沟通记录，用户端不下发。
            if (activity.action() == FeedbackActivityAction.NOTE && !viewerAdmin) {
                continue;
            }
            activities.add(new FeedbackActivityDTO(
                    activity.actorId(),
                    nameOf(nameLoader, activity.actorId()),
                    activity.action().code(),
                    activity.action().label(),
                    activity.detail(),
                    activity.createdAt(),
                    TimeSupport.formatDateTime(activity.createdAt())
            ));
        }
        List<String> assigneeIds = feedback.assigneeIds();
        List<FeedbackAssigneeDTO> assignees = new ArrayList<>();
        for (String assigneeId : assigneeIds) {
            assignees.add(FeedbackAssigneeDTO.of(assigneeId, nameOf(nameLoader, assigneeId)));
        }
        return new FeedbackDTO(
                feedback.id(),
                feedback.title(),
                feedback.description(),
                feedback.type().code(),
                feedback.type().label(),
                feedback.severity().code(),
                feedback.severity().label(),
                feedback.status().code(),
                feedback.status().label(),
                feedback.open(),
                feedback.authorId(),
                nameOf(nameLoader, feedback.authorId()),
                assigneeIds,
                assignees,
                feedback.attachments(),
                comments,
                activities,
                comments.size(),
                feedback.createdAt(),
                TimeSupport.formatDateTime(feedback.createdAt()),
                feedback.updatedAt(),
                TimeSupport.formatDateTime(feedback.updatedAt()),
                feedback.closedAt(),
                TimeSupport.formatDateTime(feedback.closedAt()),
                owned,
                owned && feedback.open(),
                owned && feedback.status().concluded(),
                feedback.autoArchiveAt(),
                TimeSupport.formatDateTime(feedback.autoArchiveAt())
        );
    }

    private static String nameOf(Function<String, String> loader, String userId) {
        if (userId == null || userId.isBlank()) {
            return "";
        }
        // 系统留痕（自动归档）没有对应成员，直接给出可读名，否则用户端会显示成「未知用户」。
        if (Feedback.SYSTEM_ACTOR.equals(userId)) {
            return "系统";
        }
        if (loader == null) {
            return "";
        }
        String name = loader.apply(userId);
        return name == null ? "" : name;
    }
}
