package online.yudream.base.plugin.activityproof.domain.repo;

import online.yudream.base.plugin.activityproof.domain.aggregate.Activity;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityParticipation;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityProofExportRecord;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityProofSettings;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityProofTemplateMembers;
import online.yudream.base.plugin.activityproof.domain.aggregate.PlayerStudentMapping;

import java.util.List;
import java.util.Optional;

public interface ActivityProofRepository {

    ActivityProofSettings settings();

    ActivityProofSettings saveSettings(ActivityProofSettings settings);

    Optional<PlayerStudentMapping> mapping(String serverId, String playerId);

    List<PlayerStudentMapping> mappings(String serverId, int page, int size);

    long countMappings(String serverId);

    PlayerStudentMapping saveMapping(PlayerStudentMapping mapping);

    void deleteMapping(String id);

    Optional<Activity> activity(String id);

    List<Activity> activities(String keyword, String status, int page, int size);

    long countActivities(String keyword, String status);

    Activity saveActivity(Activity activity);

    void deleteActivity(String id);

    Optional<ActivityParticipation> participation(String activityId, String userId);

    List<ActivityParticipation> participationsByActivity(String activityId, int page, int size);

    long countParticipationsByActivity(String activityId);

    List<ActivityParticipation> participationsByUser(String userId, int page, int size);

    long countParticipationsByUser(String userId);

    ActivityParticipation saveParticipation(ActivityParticipation participation);

    void deleteParticipation(String id);

    Optional<ActivityProofTemplateMembers> templateMembers(Long templateId);

    ActivityProofTemplateMembers saveTemplateMembers(ActivityProofTemplateMembers members);

    ActivityProofExportRecord saveExportRecord(ActivityProofExportRecord record);

    Optional<ActivityProofExportRecord> exportRecord(String id);

    List<ActivityProofExportRecord> exportRecords(int page, int size);

    long countExportRecords();

    void deleteExportRecord(String id);
}
