package online.yudream.base.plugin.aioffice.domain.aggregate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 通用数据集记录：schema 约束字段面，承接活动平台之外的业务数据（比赛、项目、值班……）。 */
public record OfficeDatasetRecord(
        String id,
        String schemaCode,
        Map<String, Object> data,
        List<OfficeFinanceRecord.FileRef> attachments,
        String createdBy,
        long createdAt,
        long updatedAt
) {

    public OfficeDatasetRecord {
        schemaCode = require(schemaCode, "数据模型编码不能为空");
        data = data == null ? Map.of() : new LinkedHashMap<>(data);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        createdBy = createdBy == null ? "" : createdBy.trim();
    }

    public static OfficeDatasetRecord create(String schemaCode, Map<String, Object> data,
                                             List<OfficeFinanceRecord.FileRef> attachments, String createdBy) {
        long now = System.currentTimeMillis();
        return new OfficeDatasetRecord(UUID.randomUUID().toString(), schemaCode, data, attachments, createdBy,
                now, now);
    }

    public OfficeDatasetRecord withData(Map<String, Object> newData) {
        return new OfficeDatasetRecord(id, schemaCode, newData, attachments, createdBy, createdAt,
                System.currentTimeMillis());
    }

    public OfficeDatasetRecord withAttachments(List<OfficeFinanceRecord.FileRef> newAttachments) {
        return new OfficeDatasetRecord(id, schemaCode, data, newAttachments, createdBy, createdAt,
                System.currentTimeMillis());
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
