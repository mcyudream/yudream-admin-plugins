package online.yudream.base.plugin.projectprogress.domain.aggregate;

import online.yudream.base.plugin.projectprogress.domain.enumerate.ProjectAssignmentMode;
import online.yudream.base.plugin.projectprogress.domain.valobj.ProjectFileEvidence;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProjectWorkDetail(
        String id,
        String projectId,
        String title,
        String description,
        String statusCode,
        ProjectAssignmentMode assignmentMode,
        int requiredAssigneeCount,
        List<String> candidateUserIds,
        List<String> assigneeUserIds,
        List<String> acceptorUserIds,
        boolean published,
        boolean pendingAcceptance,
        String acceptanceSummary,
        List<ProjectFileEvidence> acceptanceFiles,
        Long dueAt,
        long createdAt,
        long updatedAt,
        Map<String, Long> assigneeSince
) {

    public ProjectWorkDetail {
        id = requireText(id, "工作细节 ID 不能为空");
        projectId = requireText(projectId, "项目 ID 不能为空");
        title = requireText(title, "工作细节标题不能为空");
        description = text(description);
        statusCode = requireText(statusCode, "状态不能为空").toUpperCase();
        assignmentMode = assignmentMode == null ? ProjectAssignmentMode.CLAIM : assignmentMode;
        requiredAssigneeCount = Math.max(requiredAssigneeCount, 1);
        candidateUserIds = normalizeIds(candidateUserIds);
        assigneeUserIds = normalizeIds(assigneeUserIds);
        acceptorUserIds = normalizeIds(acceptorUserIds);
        acceptanceSummary = text(acceptanceSummary);
        acceptanceFiles = acceptanceFiles == null ? List.of() : List.copyOf(acceptanceFiles);
        assigneeSince = normalizeAssigneeSince(assigneeSince, assigneeUserIds);
    }

    /**
     * 某个负责人「成为该任务负责人」的时刻，0 表示无从得知。
     *
     * <p>时长类证据要按这个时刻起算：玩家接取任务之前的在线时长不该算进这次任务的时长。没有该键的
     * 老文档（本字段之前落库的细节）读出来是空表，调用方按 0 处理。
     */
    public long assigneeSinceOf(String userId) {
        Long value = assigneeSince.get(text(userId));
        return value == null ? 0L : value;
    }

    public static ProjectWorkDetail create(String projectId, String title, String description, String statusCode,
                                           ProjectAssignmentMode assignmentMode, int requiredAssigneeCount,
                                           List<String> candidateUserIds, List<String> assigneeUserIds,
                                           List<String> acceptorUserIds, Long dueAt) {
        long now = System.currentTimeMillis();
        return new ProjectWorkDetail(UUID.randomUUID().toString(), projectId, title, description, statusCode,
                assignmentMode, requiredAssigneeCount, candidateUserIds, assigneeUserIds, acceptorUserIds,
                false, false, "", List.of(), dueAt, now, now, mergeAssigneeSince(Map.of(), assigneeUserIds, List.of(), now));
    }

    public ProjectWorkDetail update(String title, String description, String statusCode, ProjectAssignmentMode assignmentMode,
                                    int requiredAssigneeCount, List<String> candidateUserIds, List<String> assigneeUserIds,
                                    List<String> acceptorUserIds, Boolean published, Long dueAt) {
        long now = System.currentTimeMillis();
        return new ProjectWorkDetail(id, projectId, title, description, statusCode, assignmentMode, requiredAssigneeCount,
                candidateUserIds, assigneeUserIds, acceptorUserIds, published == null ? this.published : published,
                pendingAcceptance, acceptanceSummary, acceptanceFiles, dueAt, createdAt, now,
                mergeAssigneeSince(assigneeSince, assigneeUserIds, this.assigneeUserIds, now));
    }

    public ProjectWorkDetail publish(List<String> assignees) {
        long now = System.currentTimeMillis();
        List<String> nextAssignees = assignees == null ? assigneeUserIds : assignees;
        return new ProjectWorkDetail(id, projectId, title, description, statusCode, assignmentMode, requiredAssigneeCount,
                candidateUserIds, nextAssignees, acceptorUserIds, true,
                pendingAcceptance, acceptanceSummary, acceptanceFiles, dueAt, createdAt, now,
                mergeAssigneeSince(assigneeSince, nextAssignees, assigneeUserIds, now));
    }

    public ProjectWorkDetail assign(List<String> assignees) {
        if (assignees == null || assignees.isEmpty()) {
            throw new IllegalArgumentException("分配用户不能为空");
        }
        long now = System.currentTimeMillis();
        return new ProjectWorkDetail(id, projectId, title, description, statusCode, assignmentMode, requiredAssigneeCount,
                candidateUserIds, assignees, acceptorUserIds, true, pendingAcceptance, acceptanceSummary,
                acceptanceFiles, dueAt, createdAt, now,
                mergeAssigneeSince(assigneeSince, assignees, assigneeUserIds, now));
    }

    public ProjectWorkDetail claim(String userId) {
        String safeUserId = requireText(userId, "认领用户不能为空");
        if (!published) {
            throw new IllegalArgumentException("任务发布后才可以认领");
        }
        if (assignmentMode != ProjectAssignmentMode.CLAIM) {
            throw new IllegalArgumentException("该工作细节不支持自主认领");
        }
        if (assigneeUserIds.contains(safeUserId)) {
            return this;
        }
        if (assigneeUserIds.size() >= requiredAssigneeCount) {
            throw new IllegalArgumentException("该工作细节认领人数已满");
        }
        if (!candidateUserIds.isEmpty() && !candidateUserIds.contains(safeUserId)) {
            throw new IllegalArgumentException("当前用户不在可认领范围内");
        }
        List<String> nextAssignees = new java.util.ArrayList<>(assigneeUserIds);
        nextAssignees.add(safeUserId);
        long now = System.currentTimeMillis();
        return new ProjectWorkDetail(id, projectId, title, description, statusCode, assignmentMode, requiredAssigneeCount,
                candidateUserIds, normalizeIds(nextAssignees), acceptorUserIds, true, pendingAcceptance,
                acceptanceSummary, acceptanceFiles, dueAt, createdAt, now,
                mergeAssigneeSince(assigneeSince, nextAssignees, assigneeUserIds, now));
    }

    public ProjectWorkDetail submitAcceptance(String reviewingStatusCode, String summary, List<ProjectFileEvidence> files) {
        if (!published) {
            throw new IllegalArgumentException("任务发布后才可以提交验收");
        }
        if (assigneeUserIds.isEmpty()) {
            throw new IllegalArgumentException("任务尚未分配负责人，不能提交验收");
        }
        String safeSummary = requireText(summary, "验收说明不能为空");
        List<ProjectFileEvidence> safeFiles = files == null ? List.of() : List.copyOf(files);
        if (safeFiles.isEmpty()) {
            throw new IllegalArgumentException("验收附件不能为空");
        }
        return new ProjectWorkDetail(id, projectId, title, description, requireText(reviewingStatusCode, "验收状态不能为空"),
                assignmentMode, requiredAssigneeCount, candidateUserIds, assigneeUserIds, acceptorUserIds, published,
                true, safeSummary, safeFiles, dueAt, createdAt, System.currentTimeMillis(), assigneeSince);
    }

    public ProjectWorkDetail accept(String doneStatusCode) {
        return new ProjectWorkDetail(id, projectId, title, description, requireText(doneStatusCode, "完成状态不能为空"),
                assignmentMode, requiredAssigneeCount, candidateUserIds, assigneeUserIds, acceptorUserIds, published,
                false, acceptanceSummary, acceptanceFiles, dueAt, createdAt, System.currentTimeMillis(), assigneeSince);
    }

    public ProjectWorkDetail reject(String resetStatusCode) {
        return new ProjectWorkDetail(id, projectId, title, description, requireText(resetStatusCode, "重置状态不能为空"),
                assignmentMode, requiredAssigneeCount, candidateUserIds, assigneeUserIds, acceptorUserIds, published,
                false, acceptanceSummary, acceptanceFiles, dueAt, createdAt, System.currentTimeMillis(), assigneeSince);
    }

    public boolean claimableBy(String userId) {
        String safeUserId = text(userId);
        return !safeUserId.isBlank()
                && published
                && assignmentMode == ProjectAssignmentMode.CLAIM
                && !assigneeUserIds.contains(safeUserId)
                && assigneeUserIds.size() < requiredAssigneeCount
                && (candidateUserIds.isEmpty() || candidateUserIds.contains(safeUserId));
    }

    public ProjectWorkDetail withStatus(String nextStatusCode) {
        return new ProjectWorkDetail(id, projectId, title, description, requireText(nextStatusCode, "状态不能为空"),
                assignmentMode, requiredAssigneeCount, candidateUserIds, assigneeUserIds, acceptorUserIds,
                published, pendingAcceptance, acceptanceSummary, acceptanceFiles, dueAt, createdAt,
                System.currentTimeMillis(), assigneeSince);
    }

    public boolean canAccept(String userId, ProjectProgressProject project) {
        String safeUserId = text(userId);
        return acceptorUserIds.contains(safeUserId) || project.canManage(safeUserId);
    }

    public boolean assignedTo(String userId) {
        return assigneeUserIds.contains(text(userId));
    }

    /**
     * 把「成为负责人」的时刻对齐到新的负责人列表：本次才进入列表的记为 {@code now}，仍在列表里的保留
     * 原时刻，被移出列表的丢弃。
     *
     * <p>只记首次出现的时刻，不做「移出再加回就重置」：一次任务被移出负责人再放回来，谁都说不清算不算
     * 重新接取，保留原时刻至少是单调、可解释的。
     *
     * <p>「之前就是负责人、但这台细节没有记录」的用户保持未知而不是记成 {@code now}：那是本字段落库
     * 之前的老数据，读的时候会退回事件流水里真实的认领时刻，在这里补一个「此刻」只会把窗口无端收窄。
     */
    private static Map<String, Long> mergeAssigneeSince(Map<String, Long> current, List<String> assignees,
                                                        List<String> previous, long now) {
        Map<String, Long> present = current == null ? Map.of() : current;
        List<String> before = normalizeIds(previous);
        Map<String, Long> next = new LinkedHashMap<>();
        for (String userId : normalizeIds(assignees)) {
            Long existing = present.get(userId);
            if (existing != null && existing > 0) {
                next.put(userId, existing);
            } else if (!before.contains(userId)) {
                next.put(userId, now);
            }
        }
        return Map.copyOf(next);
    }

    private static Map<String, Long> normalizeAssigneeSince(Map<String, Long> values, List<String> assignees) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> next = new LinkedHashMap<>();
        for (String userId : assignees) {
            Long value = values.get(userId);
            if (value != null && value > 0) {
                next.put(userId, value);
            }
        }
        return Map.copyOf(next);
    }

    private static List<String> normalizeIds(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
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
