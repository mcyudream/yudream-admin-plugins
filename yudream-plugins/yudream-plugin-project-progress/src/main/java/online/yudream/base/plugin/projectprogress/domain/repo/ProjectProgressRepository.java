package online.yudream.base.plugin.projectprogress.domain.repo;

import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectAcceptanceRecord;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectCheckInRecord;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectProgressEvent;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectProgressProject;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectWorkDetail;
import online.yudream.base.plugin.projectprogress.domain.valobj.ProjectAcceptedCheckIn;

import java.util.List;
import java.util.Optional;

public interface ProjectProgressRepository {

    ProjectProgressProject saveProject(ProjectProgressProject project);

    Optional<ProjectProgressProject> findProject(String projectId);

    List<ProjectProgressProject> listProjects(int page, int size);

    void deleteProject(String projectId);

    ProjectWorkDetail saveDetail(ProjectWorkDetail detail);

    Optional<ProjectWorkDetail> findDetail(String detailId);

    List<ProjectWorkDetail> listDetails(String projectId, int page, int size);

    List<ProjectWorkDetail> listDetailsByAssignee(String userId, int page, int size);

    List<ProjectWorkDetail> listClaimableDetails(String userId, int page, int size);

    List<ProjectWorkDetail> listPendingAcceptance(String userId, int page, int size);

    void deleteDetail(String detailId);

    ProjectCheckInRecord saveCheckIn(ProjectCheckInRecord record);

    Optional<ProjectCheckInRecord> findCheckIn(String checkInId);

    void deleteCheckIn(String checkInId);

    List<ProjectCheckInRecord> listCheckIns(String detailId, int page, int size);

    Optional<ProjectCheckInRecord> latestCheckIn(String detailId, String userId);

    List<ProjectCheckInRecord> listProjectCheckIns(String projectId, int page, int size);

    List<ProjectCheckInRecord> listCheckInsByUser(String userId, int page, int size);

    Optional<ProjectCheckInRecord> latestProjectCheckIn(String projectId, String userId);

    ProjectAcceptanceRecord saveAcceptanceRecord(ProjectAcceptanceRecord record);

    List<ProjectAcceptanceRecord> listAcceptanceRecords(String detailId, int page, int size);

    ProjectProgressEvent saveEvent(ProjectProgressEvent event);

    List<ProjectProgressEvent> listEvents(String projectId, Long since, int page, int size);

    /**
     * 某个工作细节的事件流水（按时间正序）。
     *
     * <p>用于在没有「接取时刻」字段的老细节上回溯认领时刻，因此只按 detailId 取，不按项目全量扫描。
     */
    List<ProjectProgressEvent> listDetailEvents(String detailId, int page, int size);

    /**
     * 「所属工作细节已验收通过」的打卡记录（按验收通过时间升序、同时间按打卡记录 id 升序，分页前完成排序）。
     *
     * <p>只认验收记录（{@code ProjectAcceptanceResult.ACCEPTED}）：一个细节经历多轮验收时取**最后一次**
     * 通过时间，该细节下的每条打卡记录只返回一次；{@code detailId} 为空的（挂项目而非细节的）打卡不返回；
     * <b>被驳回的打卡（{@code ProjectCheckInReviewStatus.REJECTED}）也不返回</b>。
     * {@code sinceAcceptedAt} 为含下界。</p>
     *
     * <p>呼应用例的实时回调必须与它同口径，因此二者共用同一个读模型 {@link ProjectAcceptedCheckIn}。</p>
     */
    List<ProjectAcceptedCheckIn> listAcceptedCheckIns(long sinceAcceptedAt, int page, int size);
}
