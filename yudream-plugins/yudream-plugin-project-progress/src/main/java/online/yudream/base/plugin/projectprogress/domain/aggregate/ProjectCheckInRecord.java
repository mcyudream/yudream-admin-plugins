package online.yudream.base.plugin.projectprogress.domain.aggregate;

import online.yudream.base.plugin.projectprogress.domain.enumerate.ProjectCheckInType;
import online.yudream.base.plugin.projectprogress.domain.enumerate.ProjectCheckInReviewStatus;
import online.yudream.base.plugin.projectprogress.domain.valobj.ProjectFileEvidence;
import online.yudream.base.plugin.projectprogress.domain.valobj.ProjectLocationEvidence;
import online.yudream.base.plugin.projectprogress.domain.valobj.ProjectMinecraftEvidence;

import java.util.List;
import java.util.UUID;

public record ProjectCheckInRecord(
        String id,
        String projectId,
        String detailId,
        String userId,
        ProjectCheckInType type,
        String summary,
        List<ProjectFileEvidence> files,
        ProjectLocationEvidence location,
        ProjectMinecraftEvidence minecraft,
        ProjectCheckInReviewStatus reviewStatus,
        String reviewedByUserId,
        Long reviewedAt,
        long createdAt
) {

    public ProjectCheckInRecord {
        id = requireText(id, "打卡记录 ID 不能为空");
        projectId = requireText(projectId, "项目 ID 不能为空");
        detailId = text(detailId);
        userId = requireText(userId, "打卡用户不能为空");
        if (type == null) {
            throw new IllegalArgumentException("打卡类型不能为空");
        }
        summary = text(summary);
        files = files == null ? List.of() : List.copyOf(files);
        reviewStatus = reviewStatus == null ? ProjectCheckInReviewStatus.APPROVED : reviewStatus;
        reviewedByUserId = text(reviewedByUserId);
    }

    public static ProjectCheckInRecord create(String projectId, String detailId, String userId, ProjectCheckInType type,
                                              String summary, List<ProjectFileEvidence> files,
                                              ProjectLocationEvidence location, ProjectMinecraftEvidence minecraft) {
        return new ProjectCheckInRecord(UUID.randomUUID().toString(), projectId, detailId, userId, type, summary,
                files, location, minecraft, ProjectCheckInReviewStatus.APPROVED, "", null, System.currentTimeMillis());
    }

    public ProjectCheckInRecord reject(String reviewerUserId) {
        return new ProjectCheckInRecord(id, projectId, detailId, userId, type, summary, files, location, minecraft,
                ProjectCheckInReviewStatus.REJECTED, requireText(reviewerUserId, "Reviewer user ID is required"), System.currentTimeMillis(), createdAt);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
