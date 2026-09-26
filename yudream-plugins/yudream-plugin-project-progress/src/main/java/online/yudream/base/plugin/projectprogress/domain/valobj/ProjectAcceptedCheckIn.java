package online.yudream.base.plugin.projectprogress.domain.valobj;

import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectCheckInRecord;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectProgressProject;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectWorkDetail;

/**
 * 读模型：一条「所属工作细节已验收通过」的打卡记录，及其验收通过上下文。
 *
 * <p>由仓储把「打卡记录 / 工作细节 / 项目 / 验收记录」四个集合拼好，供应用层直接映射成对外契约，
 * 避免应用层重复遍历文档。{@code acceptedAt} 是该细节**最后一次** {@code ACCEPTED} 验收记录的时间。</p>
 *
 * <p><b>不变量</b>：{@code checkIn} 一定不是被驳回的打卡（{@code reviewStatus != REJECTED}）——
 * 驳回表示这次证据不成立、不算这次打卡，因此既不产生奖励项，也不参与实时回调。</p>
 */
public record ProjectAcceptedCheckIn(
        ProjectCheckInRecord checkIn,
        ProjectWorkDetail detail,
        ProjectProgressProject project,
        long acceptedAt,
        String acceptedByUserId
) {

    public ProjectAcceptedCheckIn {
        acceptedByUserId = acceptedByUserId == null ? "" : acceptedByUserId;
    }
}
