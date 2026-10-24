package online.yudream.base.plugin.aioffice.domain.repo;

import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeCategory;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDatasetRecord;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocJob;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocType;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeFinanceRecord;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficePackage;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficePrompt;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeSchema;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeSettlementBatch;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeSettings;

import java.util.List;
import java.util.Optional;

/** AI 模板办公统一仓储契约（文档存储）。 */
public interface OfficeRepository {

    OfficeSettings settings();

    OfficeSettings saveSettings(OfficeSettings settings);

    // ---------- schema ----------

    List<OfficeSchema> schemas();

    Optional<OfficeSchema> schema(String id);

    Optional<OfficeSchema> schemaByCode(String code);

    OfficeSchema saveSchema(OfficeSchema schema);

    void deleteSchema(String id);

    // ---------- doc type ----------

    List<OfficeDocType> docTypes();

    Optional<OfficeDocType> docType(String id);

    Optional<OfficeDocType> docTypeByCode(String code);

    OfficeDocType saveDocType(OfficeDocType docType);

    void deleteDocType(String id);

    // ---------- prompt ----------

    List<OfficePrompt> prompts();

    Optional<OfficePrompt> prompt(String id);

    Optional<OfficePrompt> promptByCode(String code);

    OfficePrompt savePrompt(OfficePrompt prompt);

    void deletePrompt(String id);

    // ---------- category ----------

    List<OfficeCategory> categories();

    Optional<OfficeCategory> category(String id);

    OfficeCategory saveCategory(OfficeCategory category);

    void deleteCategory(String id);

    // ---------- package ----------

    List<OfficePackage> packages();

    Optional<OfficePackage> aPackage(String id);

    Optional<OfficePackage> packageByCode(String code);

    OfficePackage savePackage(OfficePackage aPackage);

    void deletePackage(String id);

    // ---------- dataset record ----------

    List<OfficeDatasetRecord> datasetRecords(String schemaCode, int page, int size);

    /** 创建时间落在窗口内的数据对象记录（周期聚合用，低频扫描）。 */
    List<OfficeDatasetRecord> datasetRecordsByPeriod(long fromInclusive, long toInclusive);

    long countDatasetRecords(String schemaCode);

    Optional<OfficeDatasetRecord> datasetRecord(String id);

    OfficeDatasetRecord saveDatasetRecord(OfficeDatasetRecord record);

    void deleteDatasetRecord(String id);

    // ---------- finance record ----------

    List<OfficeFinanceRecord> financeRecords(String direction, String status, String categoryCode,
                                             String createdBy, int page, int size);

    long countFinanceRecords(String direction, String status, String categoryCode, String createdBy);

    List<OfficeFinanceRecord> financeRecordsByPeriod(long fromInclusive, long toInclusive);

    Optional<OfficeFinanceRecord> financeRecord(String id);

    OfficeFinanceRecord saveFinanceRecord(OfficeFinanceRecord record);

    void deleteFinanceRecord(String id);

    // ---------- settlement batch ----------

    List<OfficeSettlementBatch> settlementBatches();

    Optional<OfficeSettlementBatch> settlementBatch(String period);

    OfficeSettlementBatch saveSettlementBatch(OfficeSettlementBatch batch);

    // ---------- doc job ----------

    List<OfficeDocJob> docJobs(String docTypeCode, String status, int page, int size);

    long countDocJobs(String docTypeCode, String status);

    List<OfficeDocJob> docJobsByTarget(String docTypeCode, String sourceType, String sourceId, String period);

    Optional<OfficeDocJob> docJob(String id);

    OfficeDocJob saveDocJob(OfficeDocJob job);

    void deleteDocJob(String id);
}
