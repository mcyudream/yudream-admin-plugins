package online.yudream.base.plugin.activityproof.domain.aggregate;

import java.util.List;

/**
 * 证明模板的默认人员：每次导出活动证明时自动并入参与者快照，不受活动参与记录与导出筛选影响。
 */
public record ActivityProofTemplateMembers(
        String id,
        Long templateId,
        List<String> userIds,
        long updatedAt,
        String updatedBy
) {
    public ActivityProofTemplateMembers {
        if (templateId == null) {
            throw new IllegalArgumentException("模板不能为空");
        }
        userIds = userIds == null ? List.of() : userIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        updatedBy = updatedBy == null ? "" : updatedBy.trim();
        id = id == null || id.isBlank() ? id(templateId) : id.trim();
    }

    public static ActivityProofTemplateMembers empty(Long templateId) {
        return new ActivityProofTemplateMembers(null, templateId, List.of(), 0, "");
    }

    public ActivityProofTemplateMembers withUserIds(List<String> nextUserIds, String operatorUserId) {
        return new ActivityProofTemplateMembers(id, templateId, nextUserIds, System.currentTimeMillis(), operatorUserId);
    }

    public static String id(Long templateId) {
        if (templateId == null) {
            throw new IllegalArgumentException("模板不能为空");
        }
        return "template:" + templateId;
    }
}
