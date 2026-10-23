package online.yudream.base.plugin.aioffice.domain.aggregate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 文档生成任务：AI 草稿 → 人工编辑 → 定稿渲染 docx。 */
public record OfficeDocJob(
        String id,
        String docTypeCode,
        String sourceType,
        String sourceId,
        String period,
        String status,
        Map<String, String> draft,
        List<String> aiRuns,
        String resultFileKey,
        String resultFilename,
        String error,
        String createdBy,
        long createdAt,
        long updatedAt
) {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_GENERATING = "generating";
    public static final String STATUS_DRAFTED = "drafted";
    public static final String STATUS_FINAL = "final";
    public static final String STATUS_FAILED = "failed";
    public static final String SOURCE_ACTIVITY = "ACTIVITY";
    public static final String SOURCE_RECORD = "RECORD";
    public static final String SOURCE_MONTH = "MONTH";

    public OfficeDocJob {
        docTypeCode = require(docTypeCode, "文档类型不能为空");
        sourceType = sourceType == null || sourceType.isBlank()
                ? SOURCE_MONTH : sourceType.trim().toUpperCase(Locale.ROOT);
        sourceId = sourceId == null ? "" : sourceId.trim();
        period = period == null ? "" : period.trim();
        status = status == null || status.isBlank() ? STATUS_DRAFT : status.trim().toLowerCase(Locale.ROOT);
        draft = draft == null ? Map.of() : new LinkedHashMap<>(draft);
        aiRuns = aiRuns == null ? List.of() : List.copyOf(aiRuns);
        resultFileKey = text(resultFileKey);
        resultFilename = text(resultFilename);
        error = text(error);
        createdBy = text(createdBy);
    }

    public static OfficeDocJob create(String docTypeCode, String sourceType, String sourceId, String period,
                                      String createdBy) {
        long now = System.currentTimeMillis();
        return new OfficeDocJob(UUID.randomUUID().toString(), docTypeCode, sourceType, sourceId, period,
                STATUS_DRAFT, Map.of(), List.of(), "", "", "", createdBy, now, now);
    }

    public OfficeDocJob withDraft(Map<String, String> newDraft, String status) {
        return new OfficeDocJob(id, docTypeCode, sourceType, sourceId, period, status, newDraft, aiRuns,
                resultFileKey, resultFilename, error, createdBy, createdAt, System.currentTimeMillis());
    }

    public OfficeDocJob withAiDraft(String variable, String content, String runId) {
        Map<String, String> next = new LinkedHashMap<>(draft);
        next.put(variable, content);
        List<String> runs = new java.util.ArrayList<>(aiRuns);
        if (runId != null && !runId.isBlank()) {
            runs.add(runId);
        }
        return new OfficeDocJob(id, docTypeCode, sourceType, sourceId, period, STATUS_DRAFTED, next, runs,
                resultFileKey, resultFilename, "", createdBy, createdAt, System.currentTimeMillis());
    }

    public OfficeDocJob withResult(String fileKey, String filename) {
        return new OfficeDocJob(id, docTypeCode, sourceType, sourceId, period, STATUS_FINAL, draft, aiRuns,
                fileKey, filename, "", createdBy, createdAt, System.currentTimeMillis());
    }

    public OfficeDocJob withError(String message) {
        return new OfficeDocJob(id, docTypeCode, sourceType, sourceId, period, STATUS_FAILED, draft, aiRuns,
                resultFileKey, resultFilename, message, createdBy, createdAt, System.currentTimeMillis());
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
