package online.yudream.base.plugin.activityproof.infrastructure.repository;

import online.yudream.base.plugin.activityproof.domain.aggregate.Activity;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityParticipation;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityProofExportRecord;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityProofParticipantSnapshot;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityProofSettings;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityProofTemplateMembers;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityQuizAttempt;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityQuizConfig;
import online.yudream.base.plugin.activityproof.domain.aggregate.PlayerStudentMapping;
import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityBindingType;
import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityDeptMode;
import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityStatus;
import online.yudream.base.plugin.activityproof.domain.enumerate.ParticipationSource;
import online.yudream.base.plugin.activityproof.domain.enumerate.ParticipationStatus;
import online.yudream.base.plugin.activityproof.domain.enumerate.VerifyStatus;
import online.yudream.base.plugin.activityproof.domain.repo.ActivityProofRepository;
import online.yudream.base.plugin.activityproof.domain.valobj.ActivityBinding;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class ActivityProofDocumentRepository implements ActivityProofRepository {

    private static final String SETTINGS = "settings";
    private static final String MAPPINGS = "mappings";
    private static final String EXPORTS = "exports";
    private static final String ACTIVITIES = "activities";
    private static final String PARTICIPATIONS = "participations";
    private static final String QUIZ_CONFIGS = "quiz_configs";
    private static final String QUIZ_ATTEMPTS = "quiz_attempts";
    private static final String TEMPLATE_MEMBERS = "template_members";
    private static final String AUTO_JOIN_EXCLUSIONS = "auto_join_exclusions";
    private static final int SCAN_PAGE_SIZE = 200;

    private final PluginDocumentStore documents;

    public ActivityProofDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public ActivityProofSettings settings() {
        return documents.findById(SETTINGS, ActivityProofSettings.ID)
                .map(this::toSettings)
                .orElseGet(ActivityProofSettings::empty);
    }

    @Override
    public ActivityProofSettings saveSettings(ActivityProofSettings settings) {
        return toSettings(documents.save(SETTINGS, ActivityProofSettings.ID, settingsDocument(settings)));
    }

    @Override
    public Optional<PlayerStudentMapping> mapping(String serverId, String playerId) {
        return documents.findById(MAPPINGS, PlayerStudentMapping.id(serverId, playerId)).map(this::toMapping);
    }

    @Override
    public List<PlayerStudentMapping> mappings(String serverId, int page, int size) {
        List<Map<String, Object>> rows = serverId == null || serverId.isBlank()
                ? documents.findAll(MAPPINGS, page, size)
                : documents.findByField(MAPPINGS, "serverId", serverId, page, size);
        return rows.stream().map(this::toMapping).toList();
    }

    @Override
    public long countMappings(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return documents.count(MAPPINGS);
        }
        long total = 0;
        int page = 1;
        while (true) {
            List<Map<String, Object>> rows = documents.findByField(MAPPINGS, "serverId", serverId, page, 200);
            total += rows.size();
            if (rows.size() < 200) {
                return total;
            }
            page++;
        }
    }

    @Override
    public PlayerStudentMapping saveMapping(PlayerStudentMapping mapping) {
        return toMapping(documents.save(MAPPINGS, mapping.id(), mappingDocument(mapping)));
    }

    @Override
    public void deleteMapping(String id) {
        documents.delete(MAPPINGS, id);
    }

    @Override
    public Optional<Activity> activity(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(ACTIVITIES, id.trim()).map(this::toActivity);
    }

    @Override
    public List<Activity> activities(String keyword, String status, int page, int size) {
        List<Activity> filtered = filteredActivities(keyword, status);
        return pageOf(filtered, page, size);
    }

    @Override
    public long countActivities(String keyword, String status) {
        return filteredActivities(keyword, status).size();
    }

    @Override
    public Activity saveActivity(Activity activity) {
        return toActivity(documents.save(ACTIVITIES, activity.id(), activityDocument(activity)));
    }

    @Override
    public void deleteActivity(String id) {
        documents.delete(ACTIVITIES, id);
    }

    @Override
    public Optional<ActivityParticipation> participation(String activityId, String userId) {
        if (activityId == null || activityId.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(PARTICIPATIONS, ActivityParticipation.id(activityId, userId)).map(this::toParticipation);
    }

    @Override
    public List<ActivityParticipation> participationsByActivity(String activityId, int page, int size) {
        List<ActivityParticipation> filtered = scanParticipations().stream()
                .filter(item -> Objects.equals(item.activityId(), activityId))
                .toList();
        return pageOf(filtered, page, size);
    }

    @Override
    public long countParticipationsByActivity(String activityId) {
        return scanParticipations().stream().filter(item -> Objects.equals(item.activityId(), activityId)).count();
    }

    @Override
    public List<ActivityParticipation> participationsByUser(String userId, int page, int size) {
        List<ActivityParticipation> filtered = scanParticipations().stream()
                .filter(item -> Objects.equals(item.userId(), userId))
                .toList();
        return pageOf(filtered, page, size);
    }

    @Override
    public long countParticipationsByUser(String userId) {
        return scanParticipations().stream().filter(item -> Objects.equals(item.userId(), userId)).count();
    }

    @Override
    public ActivityParticipation saveParticipation(ActivityParticipation participation) {
        return toParticipation(documents.save(PARTICIPATIONS, participation.id(), participationDocument(participation)));
    }

    @Override
    public void deleteParticipation(String id) {
        documents.delete(PARTICIPATIONS, id);
    }

    @Override
    public boolean autoJoinExcluded(String activityId, String userId) {
        if (activityId == null || activityId.isBlank() || userId == null || userId.isBlank()) {
            return false;
        }
        return documents.findById(AUTO_JOIN_EXCLUSIONS, activityId.trim() + ":" + userId.trim()).isPresent();
    }

    @Override
    public void addAutoJoinExclusion(String activityId, String userId) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", activityId.trim() + ":" + userId.trim());
        document.put("activityId", activityId.trim());
        document.put("userId", userId.trim());
        document.put("excludedAt", System.currentTimeMillis());
        documents.save(AUTO_JOIN_EXCLUSIONS, string(document, "id"), document);
    }

    @Override
    public Optional<ActivityQuizConfig> quizConfig(String activityId) {
        if (activityId == null || activityId.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(QUIZ_CONFIGS, activityId.trim()).map(this::toQuizConfig);
    }

    @Override
    public ActivityQuizConfig saveQuizConfig(ActivityQuizConfig config) {
        return toQuizConfig(documents.save(QUIZ_CONFIGS, config.activityId(), quizConfigDocument(config)));
    }

    @Override
    public void deleteQuizConfig(String activityId) {
        if (activityId != null && !activityId.isBlank()) {
            documents.delete(QUIZ_CONFIGS, activityId.trim());
        }
    }

    @Override
    public Optional<ActivityQuizAttempt> quizAttempt(String activityId, String userId) {
        if (activityId == null || activityId.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(QUIZ_ATTEMPTS, ActivityQuizAttempt.id(activityId, userId)).map(this::toQuizAttempt);
    }

    @Override
    public List<ActivityQuizAttempt> quizAttemptsByActivity(String activityId) {
        if (activityId == null || activityId.isBlank()) {
            return List.of();
        }
        String target = activityId.trim();
        return scan(QUIZ_ATTEMPTS).stream()
                .filter(row -> target.equals(string(row, "activityId")))
                .map(this::toQuizAttempt)
                .toList();
    }

    @Override
    public ActivityQuizAttempt saveQuizAttempt(ActivityQuizAttempt attempt) {
        return toQuizAttempt(documents.save(QUIZ_ATTEMPTS, attempt.id(), quizAttemptDocument(attempt)));
    }

    @Override
    public void deleteQuizAttempt(String id) {
        if (id != null && !id.isBlank()) {
            documents.delete(QUIZ_ATTEMPTS, id.trim());
        }
    }

    @Override
    public void deleteAutoJoinExclusionsByActivity(String activityId) {
        deleteByActivityId(AUTO_JOIN_EXCLUSIONS, activityId);
    }

    @Override
    public Optional<ActivityProofTemplateMembers> templateMembers(Long templateId) {
        if (templateId == null) {
            return Optional.empty();
        }
        return documents.findById(TEMPLATE_MEMBERS, ActivityProofTemplateMembers.id(templateId)).map(this::toTemplateMembers);
    }

    @Override
    public ActivityProofTemplateMembers saveTemplateMembers(ActivityProofTemplateMembers members) {
        return toTemplateMembers(documents.save(TEMPLATE_MEMBERS, members.id(), templateMembersDocument(members)));
    }

    @Override
    public ActivityProofExportRecord saveExportRecord(ActivityProofExportRecord record) {
        return toExport(documents.save(EXPORTS, record.id(), exportDocument(record)));
    }

    @Override
    public Optional<ActivityProofExportRecord> exportRecord(String id) {
        return documents.findById(EXPORTS, id).map(this::toExport);
    }

    @Override
    public List<ActivityProofExportRecord> exportRecords(int page, int size) {
        List<ActivityProofExportRecord> all = scan(EXPORTS).stream()
                .map(this::toExport)
                .sorted(Comparator.comparingLong(ActivityProofExportRecord::generatedAt).reversed())
                .toList();
        return pageOf(all, page, size);
    }

    @Override
    public long countExportRecords() {
        return documents.count(EXPORTS);
    }

    @Override
    public void deleteExportRecord(String id) {
        documents.delete(EXPORTS, id);
    }

    private List<Activity> filteredActivities(String keyword, String status) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return scan(ACTIVITIES).stream()
                .map(this::toActivity)
                .filter(item -> normalizedStatus.isBlank() || item.status().name().equals(normalizedStatus))
                .filter(item -> normalizedKeyword.isBlank()
                        || item.title().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                        || item.summary().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                .sorted(Comparator.comparingLong(Activity::createdAt).reversed())
                .toList();
    }

    private List<ActivityParticipation> scanParticipations() {
        return scan(PARTICIPATIONS).stream()
                .map(this::toParticipation)
                .sorted(Comparator.comparingLong(ActivityParticipation::joinedAt).reversed())
                .toList();
    }

    private List<Map<String, Object>> scan(String collection) {
        List<Map<String, Object>> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(collection, page, SCAN_PAGE_SIZE);
            result.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                return result;
            }
            page++;
        }
    }

    private void deleteByActivityId(String collection, String activityId) {
        if (activityId == null || activityId.isBlank()) {
            return;
        }
        String target = activityId.trim();
        for (Map<String, Object> row : scan(collection)) {
            if (!target.equals(string(row, "activityId"))) {
                continue;
            }
            String id = string(row, "id");
            if (!id.isBlank()) {
                documents.delete(collection, id);
            }
        }
    }

    private <T> List<T> pageOf(List<T> records, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(Math.min(size <= 0 ? 20 : size, 200), 1);
        int from = Math.min((safePage - 1) * safeSize, records.size());
        int to = Math.min(from + safeSize, records.size());
        return records.subList(from, to);
    }

    private Map<String, Object> settingsDocument(ActivityProofSettings settings) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", ActivityProofSettings.ID);
        document.put("templateId", settings.templateId());
        document.put("templateCode", settings.templateCode());
        document.put("templateName", settings.templateName());
        document.put("templateFilename", settings.templateFilename());
        document.put("templateUpdatedAt", settings.templateUpdatedAt());
        document.put("defaultActivityName", settings.defaultActivityName());
        document.put("defaultCollege", settings.defaultCollege());
        document.put("defaultIssuer", settings.defaultIssuer());
        document.put("qqNotifyEnabled", settings.qqNotifyEnabled());
        document.put("qqConnectionId", settings.qqConnectionId());
        document.put("qqGroupIds", new ArrayList<>(settings.qqGroupIds()));
        document.put("qqMessageTemplate", settings.qqMessageTemplate());
        document.put("qqSignupButtonEnabled", settings.qqSignupButtonEnabled());
        document.put("qqSignupButtonLabel", settings.qqSignupButtonLabel());
        document.put("updatedAt", settings.updatedAt());
        return stripNulls(document);
    }

    private Map<String, Object> templateMembersDocument(ActivityProofTemplateMembers members) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", members.id());
        document.put("templateId", members.templateId());
        document.put("userIds", new ArrayList<>(members.userIds()));
        document.put("updatedAt", members.updatedAt());
        document.put("updatedBy", members.updatedBy());
        return stripNulls(document);
    }

    private Map<String, Object> mappingDocument(PlayerStudentMapping mapping) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", mapping.id());
        document.put("serverId", mapping.serverId());
        document.put("playerId", mapping.playerId());
        document.put("playerName", mapping.playerName());
        document.put("studentNo", mapping.studentNo());
        document.put("createdAt", mapping.createdAt());
        document.put("updatedAt", mapping.updatedAt());
        return stripNulls(document);
    }

    private Map<String, Object> activityDocument(Activity activity) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", activity.id());
        document.put("title", activity.title());
        document.put("summary", activity.summary());
        document.put("description", activity.description());
        document.put("coverUrl", activity.coverUrl());
        document.put("signupStart", activity.signupStart());
        document.put("signupEnd", activity.signupEnd());
        document.put("activityStart", activity.activityStart());
        document.put("activityEnd", activity.activityEnd());
        document.put("status", activity.status().name());
        document.put("deptMode", activity.deptMode().name());
        document.put("allowedDeptIds", new ArrayList<>(activity.allowedDeptIds()));
        document.put("bindings", activity.bindings().stream().map(this::bindingDocument).toList());
        document.put("createdBy", activity.createdBy());
        document.put("createdAt", activity.createdAt());
        document.put("updatedAt", activity.updatedAt());
        document.put("publishedAt", activity.publishedAt());
        return stripNulls(document);
    }

    private Map<String, Object> bindingDocument(ActivityBinding binding) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("type", binding.type().name());
        document.put("serverId", binding.serverId());
        document.put("minOnlineMinutes", binding.minOnlineMinutes());
        document.put("includeAfk", binding.includeAfk());
        document.put("autoJoin", binding.autoJoin());
        document.put("formCode", binding.formCode());
        document.put("formName", binding.formName());
        return stripNulls(document);
    }

    private Map<String, Object> participationDocument(ActivityParticipation participation) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", participation.id());
        document.put("activityId", participation.activityId());
        document.put("userId", participation.userId());
        document.put("status", participation.status().name());
        document.put("joinedAt", participation.joinedAt());
        document.put("cancelledAt", participation.cancelledAt());
        document.put("verifyStatus", participation.verifyStatus().name());
        document.put("verifiedAt", participation.verifiedAt());
        document.put("verifyNote", participation.verifyNote());
        document.put("source", participation.source().name());
        return stripNulls(document);
    }

    private Map<String, Object> exportDocument(ActivityProofExportRecord record) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", record.id());
        document.put("activityId", record.activityId());
        document.put("serverId", record.serverId());
        document.put("serverName", record.serverName());
        document.put("activityName", record.activityName());
        document.put("outputObjectKey", record.outputObjectKey());
        document.put("outputFilename", record.outputFilename());
        document.put("participantCount", record.participantCount());
        document.put("unmatchedCount", record.unmatchedCount());
        document.put("operatorUserId", record.operatorUserId());
        document.put("generatedAt", record.generatedAt());
        document.put("stampedPdfObjectKey", record.stampedPdfObjectKey());
        document.put("stampedPdfFilename", record.stampedPdfFilename());
        document.put("stampedPdfContentType", record.stampedPdfContentType());
        document.put("stampedPdfSize", record.stampedPdfSize());
        document.put("stampedPdfUploadedAt", record.stampedPdfUploadedAt());
        document.put("participants", record.participants().stream().map(this::snapshotDocument).toList());
        return stripNulls(document);
    }

    private Map<String, Object> snapshotDocument(ActivityProofParticipantSnapshot snapshot) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("userId", snapshot.userId());
        document.put("studentName", snapshot.studentName());
        document.put("studentNo", snapshot.studentNo());
        document.put("className", snapshot.className());
        document.put("college", snapshot.college());
        document.put("playerId", snapshot.playerId());
        document.put("playerName", snapshot.playerName());
        return stripNulls(document);
    }

    private Map<String, Object> quizConfigDocument(ActivityQuizConfig config) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", config.activityId());
        document.put("enabled", config.enabled());
        document.put("categoryId", config.categoryId());
        document.put("tags", new ArrayList<>(config.tags()));
        document.put("types", new ArrayList<>(config.types()));
        document.put("difficulties", new ArrayList<>(config.difficulties()));
        document.put("count", config.count());
        document.put("passCorrect", config.passCorrect());
        document.put("subjectiveMode", config.subjectiveMode());
        document.put("updatedAt", config.updatedAt());
        return stripNulls(document);
    }

    private Map<String, Object> quizAttemptDocument(ActivityQuizAttempt attempt) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", attempt.id());
        document.put("activityId", attempt.activityId());
        document.put("userId", attempt.userId());
        document.put("sessionId", attempt.sessionId());
        document.put("attempts", attempt.attempts());
        document.put("passed", attempt.passed());
        document.put("passedAt", attempt.passedAt());
        document.put("updatedAt", attempt.updatedAt());
        return stripNulls(document);
    }

    private ActivityQuizConfig toQuizConfig(Map<String, Object> document) {
        return new ActivityQuizConfig(
                string(document, "id"),
                bool(document.get("enabled")),
                string(document, "categoryId"),
                stringList(document.get("tags")),
                stringList(document.get("types")),
                intList(document.get("difficulties")),
                integer(document, "count", 0),
                integer(document, "passCorrect", 0),
                string(document, "subjectiveMode"),
                number(document, "updatedAt", 0)
        );
    }

    private ActivityQuizAttempt toQuizAttempt(Map<String, Object> document) {
        return new ActivityQuizAttempt(
                string(document, "id"),
                string(document, "activityId"),
                string(document, "userId"),
                string(document, "sessionId"),
                integer(document, "attempts", 0),
                bool(document.get("passed")),
                number(document, "passedAt", 0),
                number(document, "updatedAt", 0)
        );
    }

    private List<Integer> intList(Object value) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Number number) {
                result.add(number.intValue());
            } else if (row != null && !String.valueOf(row).isBlank()) {
                result.add(Integer.parseInt(String.valueOf(row)));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> stripNulls(Map<String, Object> document) {
        document.values().removeIf(Objects::isNull);
        return document;
    }

    private ActivityProofSettings toSettings(Map<String, Object> document) {
        return new ActivityProofSettings(
                string(document, "id"),
                longObject(document, "templateId"),
                string(document, "templateCode"),
                string(document, "templateName"),
                string(document, "templateFilename"),
                number(document, "templateUpdatedAt", 0),
                string(document, "defaultActivityName"),
                string(document, "defaultCollege"),
                string(document, "defaultIssuer"),
                bool(document.get("qqNotifyEnabled")),
                string(document, "qqConnectionId"),
                stringList(document.get("qqGroupIds")),
                string(document, "qqMessageTemplate"),
                bool(document.get("qqSignupButtonEnabled")),
                string(document, "qqSignupButtonLabel"),
                number(document, "updatedAt", 0)
        );
    }

    private ActivityProofTemplateMembers toTemplateMembers(Map<String, Object> document) {
        return new ActivityProofTemplateMembers(
                string(document, "id"),
                longObject(document, "templateId"),
                stringList(document.get("userIds")),
                number(document, "updatedAt", 0),
                string(document, "updatedBy")
        );
    }

    private PlayerStudentMapping toMapping(Map<String, Object> document) {
        return new PlayerStudentMapping(
                string(document, "id"),
                string(document, "serverId"),
                string(document, "playerId"),
                string(document, "playerName"),
                string(document, "studentNo"),
                number(document, "createdAt", 0),
                number(document, "updatedAt", 0)
        );
    }

    private Activity toActivity(Map<String, Object> document) {
        return new Activity(
                string(document, "id"),
                string(document, "title"),
                string(document, "summary"),
                string(document, "description"),
                string(document, "coverUrl"),
                number(document, "signupStart", 0),
                number(document, "signupEnd", 0),
                number(document, "activityStart", 0),
                number(document, "activityEnd", 0),
                ActivityStatus.of(string(document, "status")),
                ActivityDeptMode.of(string(document, "deptMode")),
                stringList(document.get("allowedDeptIds")),
                bindings(document.get("bindings")),
                string(document, "createdBy"),
                number(document, "createdAt", 0),
                number(document, "updatedAt", 0),
                number(document, "publishedAt", 0)
        );
    }

    private List<ActivityBinding> bindings(Object value) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream()
                .filter(row -> row instanceof Map<?, ?>)
                .map(row -> toBinding((Map<?, ?>) row))
                .filter(Objects::nonNull)
                .toList();
    }

    private ActivityBinding toBinding(Map<?, ?> document) {
        ActivityBindingType type = ActivityBindingType.of(string(document, "type"));
        if (type == null) {
            return null;
        }
        return new ActivityBinding(
                type,
                string(document, "serverId"),
                (int) number(document, "minOnlineMinutes", 0),
                bool(document.get("includeAfk")),
                bool(document.get("autoJoin")),
                string(document, "formCode"),
                string(document, "formName")
        );
    }

    private ActivityParticipation toParticipation(Map<String, Object> document) {
        return new ActivityParticipation(
                string(document, "id"),
                string(document, "activityId"),
                string(document, "userId"),
                ParticipationStatus.of(string(document, "status")),
                number(document, "joinedAt", 0),
                number(document, "cancelledAt", 0),
                VerifyStatus.of(string(document, "verifyStatus")),
                number(document, "verifiedAt", 0),
                string(document, "verifyNote"),
                ParticipationSource.of(string(document, "source"))
        );
    }

    private ActivityProofExportRecord toExport(Map<String, Object> document) {
        return new ActivityProofExportRecord(
                string(document, "id"),
                string(document, "activityId"),
                string(document, "serverId"),
                string(document, "serverName"),
                string(document, "activityName"),
                string(document, "outputObjectKey"),
                string(document, "outputFilename"),
                integer(document, "participantCount", 0),
                integer(document, "unmatchedCount", 0),
                string(document, "operatorUserId"),
                number(document, "generatedAt", 0),
                string(document, "stampedPdfObjectKey"),
                string(document, "stampedPdfFilename"),
                string(document, "stampedPdfContentType"),
                number(document, "stampedPdfSize", 0),
                number(document, "stampedPdfUploadedAt", 0),
                snapshots(document.get("participants"))
        );
    }

    private List<ActivityProofParticipantSnapshot> snapshots(Object value) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream()
                .filter(row -> row instanceof Map<?, ?>)
                .map(row -> toSnapshot((Map<?, ?>) row))
                .toList();
    }

    private ActivityProofParticipantSnapshot toSnapshot(Map<?, ?> document) {
        return new ActivityProofParticipantSnapshot(
                string(document, "userId"),
                string(document, "studentName"),
                string(document, "studentNo"),
                string(document, "className"),
                string(document, "college"),
                string(document, "playerId"),
                string(document, "playerName")
        );
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String string(Map<?, ?> document, String key) {
        Object value = document.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private long number(Map<?, ?> document, String key, long defaultValue) {
        Object value = document.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null || String.valueOf(value).isBlank() ? defaultValue : Long.parseLong(String.valueOf(value));
    }

    private Long longObject(Map<?, ?> document, String key) {
        Object value = document.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null || String.valueOf(value).isBlank() ? null : Long.parseLong(String.valueOf(value));
    }

    private int integer(Map<?, ?> document, String key, int defaultValue) {
        Object value = document.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null || String.valueOf(value).isBlank() ? defaultValue : Integer.parseInt(String.valueOf(value));
    }
}
