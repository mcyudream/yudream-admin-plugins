package online.yudream.base.plugin.todo.application.dto;

/**
 * 任务库条目：供「从已有任务添加 / 派发」选择的可复用任务。
 *
 * <p>只暴露复用所需的字段，不回传备注全文以外的小任务明细；{@code inMyList} 由服务层按
 * 请求者清单计算，前端据此置灰，避免把已有任务重复加进个人清单。
 */
public record TodoTemplateDTO(
        String id,
        String title,
        String remark,
        String priority,
        int subTaskTotal,
        String ownerName,
        /** 任务库可见范围：PRIVATE / DEPT / PUBLIC。 */
        String visibility,
        /** 请求者清单中是否已存在同名未完成任务。 */
        boolean inMyList
) {
}
