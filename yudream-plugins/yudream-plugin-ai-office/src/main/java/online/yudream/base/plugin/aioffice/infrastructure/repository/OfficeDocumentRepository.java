package online.yudream.base.plugin.aioffice.infrastructure.repository;

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
import online.yudream.base.plugin.aioffice.domain.repo.OfficeRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 文档存储实现：插件传短集合名，宿主自动加插件前缀并做隔离。 */
public class OfficeDocumentRepository implements OfficeRepository {

    private static final String SETTINGS = "settings";
    private static final String SCHEMAS = "schemas";
    private static final String DOC_TYPES = "doc_types";
    private static final String PROMPTS = "prompts";
    private static final String CATEGORIES = "categories";
    private static final String PACKAGES = "packages";
    private static final String DATASET_RECORDS = "dataset_records";
    private static final String FINANCE_RECORDS = "finance_records";
    private static final String SETTLEMENT_BATCHES = "settlement_batches";
    private static final String DOC_JOBS = "doc_jobs";

    private final PluginDocumentStore documents;

    public OfficeDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    // ---------- settings ----------

    @Override
    public OfficeSettings settings() {
        return documents.findById(SETTINGS, OfficeSettings.ID)
                .map(OfficeDocumentRepository::toSettings)
                .orElseGet(OfficeSettings::empty);
    }

    @Override
    public OfficeSettings saveSettings(OfficeSettings settings) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", OfficeSettings.ID);
        document.put("profileSchemaCode", settings.profileSchemaCode());
        document.put("profile", new LinkedHashMap<>(settings.profile()));
        document.put("financeCycle", settings.financeCycle());
        document.put("currency", settings.currency());
        document.put("styleGuide", settings.styleGuide());
        document.put("aiProviderCode", settings.aiProviderCode());
        document.put("aiModelCode", settings.aiModelCode());
        document.put("wordImageEmbed", settings.wordImageEmbed());
        document.put("activePresetCode", settings.activePresetCode());
        document.put("updatedAt", settings.updatedAt());
        documents.save(SETTINGS, OfficeSettings.ID, document);
        return settings;
    }

    private static OfficeSettings toSettings(Map<String, Object> document) {
        Map<String, Object> profile = new LinkedHashMap<>();
        if (document.get("profile") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> profile.put(String.valueOf(key), value));
        }
        return new OfficeSettings(text(document, "profileSchemaCode"), profile, text(document, "financeCycle"),
                text(document, "currency"), text(document, "styleGuide"), text(document, "aiProviderCode"),
                text(document, "aiModelCode"), boolOrNull(document, "wordImageEmbed"),
                text(document, "activePresetCode"), num(document, "updatedAt"));
    }

    private static Boolean boolOrNull(Map<?, ?> document, String key) {
        Object value = document.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null ? null : Boolean.parseBoolean(String.valueOf(value));
    }

    // ---------- schema ----------

    @Override
    public List<OfficeSchema> schemas() {
        return all(SCHEMAS).stream().map(OfficeDocumentRepository::toSchema)
                .sorted((a, b) -> a.code().compareToIgnoreCase(b.code())).toList();
    }

    @Override
    public Optional<OfficeSchema> schema(String id) {
        return documents.findById(SCHEMAS, id).map(OfficeDocumentRepository::toSchema);
    }

    @Override
    public Optional<OfficeSchema> schemaByCode(String code) {
        return schemas().stream().filter(schema -> schema.code().equals(code)).findFirst();
    }

    @Override
    public OfficeSchema saveSchema(OfficeSchema schema) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", schema.id());
        document.put("code", schema.code());
        document.put("name", schema.name());
        document.put("builtin", schema.builtin());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (OfficeSchema.FieldDef field : schema.fields()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", field.key());
            item.put("label", field.label());
            item.put("type", field.type());
            item.put("required", field.required());
            item.put("options", new ArrayList<>(field.options()));
            fields.add(item);
        }
        document.put("fields", fields);
        document.put("updatedAt", schema.updatedAt());
        documents.save(SCHEMAS, schema.id(), document);
        return schema;
    }

    @Override
    public void deleteSchema(String id) {
        documents.delete(SCHEMAS, id);
    }

    private static OfficeSchema toSchema(Map<String, Object> document) {
        List<OfficeSchema.FieldDef> fields = new ArrayList<>();
        if (document.get("fields") instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof Map<?, ?> map) {
                    List<String> options = new ArrayList<>();
                    if (map.get("options") instanceof List<?> optionList) {
                        optionList.forEach(option -> options.add(String.valueOf(option)));
                    }
                    fields.add(new OfficeSchema.FieldDef(text(map, "key"), text(map, "label"),
                            text(map, "type"), bool(map, "required"), options));
                }
            }
        }
        return new OfficeSchema(text(document, "id"), text(document, "code"), text(document, "name"),
                bool(document, "builtin"), fields, num(document, "updatedAt"));
    }

    // ---------- doc type ----------

    @Override
    public List<OfficeDocType> docTypes() {
        return all(DOC_TYPES).stream().map(OfficeDocumentRepository::toDocType)
                .sorted((a, b) -> a.code().compareToIgnoreCase(b.code())).toList();
    }

    @Override
    public Optional<OfficeDocType> docType(String id) {
        return documents.findById(DOC_TYPES, id).map(OfficeDocumentRepository::toDocType);
    }

    @Override
    public Optional<OfficeDocType> docTypeByCode(String code) {
        return docTypes().stream().filter(type -> type.code().equals(code)).findFirst();
    }

    @Override
    public OfficeDocType saveDocType(OfficeDocType docType) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", docType.id());
        document.put("code", docType.code());
        document.put("name", docType.name());
        document.put("stage", docType.stage());
        document.put("wordTemplateCode", docType.wordTemplateCode());
        document.put("schemaCodes", new ArrayList<>(docType.schemaCodes()));
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (OfficeDocType.Mapping mapping : docType.mappings()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("var", mapping.var());
            item.put("source", mapping.source());
            item.put("transform", mapping.transform());
            item.put("itemMapping", new LinkedHashMap<>(mapping.itemMapping()));
            mappings.add(item);
        }
        document.put("mappings", mappings);
        document.put("fileNamePattern", docType.fileNamePattern());
        document.put("enabled", docType.enabled());
        document.put("updatedAt", docType.updatedAt());
        documents.save(DOC_TYPES, docType.id(), document);
        return docType;
    }

    @Override
    public void deleteDocType(String id) {
        documents.delete(DOC_TYPES, id);
    }

    private static OfficeDocType toDocType(Map<String, Object> document) {
        List<OfficeDocType.Mapping> mappings = new ArrayList<>();
        if (document.get("mappings") instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, String> itemMapping = new LinkedHashMap<>();
                    if (map.get("itemMapping") instanceof Map<?, ?> rawItem) {
                        rawItem.forEach((key, value) -> itemMapping.put(String.valueOf(key), String.valueOf(value)));
                    }
                    mappings.add(new OfficeDocType.Mapping(text(map, "var"), text(map, "source"),
                            text(map, "transform"), itemMapping));
                }
            }
        }
        List<String> schemaCodes = new ArrayList<>();
        if (document.get("schemaCodes") instanceof List<?> codes) {
            codes.forEach(code -> schemaCodes.add(String.valueOf(code)));
        }
        return new OfficeDocType(text(document, "id"), text(document, "code"), text(document, "name"),
                text(document, "stage"), text(document, "wordTemplateCode"), schemaCodes, mappings,
                text(document, "fileNamePattern"), bool(document, "enabled"), num(document, "updatedAt"));
    }

    // ---------- prompt ----------

    @Override
    public List<OfficePrompt> prompts() {
        return all(PROMPTS).stream().map(OfficeDocumentRepository::toPrompt)
                .sorted((a, b) -> a.code().compareToIgnoreCase(b.code())).toList();
    }

    @Override
    public Optional<OfficePrompt> prompt(String id) {
        return documents.findById(PROMPTS, id).map(OfficeDocumentRepository::toPrompt);
    }

    @Override
    public Optional<OfficePrompt> promptByCode(String code) {
        return prompts().stream().filter(prompt -> prompt.code().equals(code)).findFirst();
    }

    @Override
    public OfficePrompt savePrompt(OfficePrompt prompt) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", prompt.id());
        document.put("code", prompt.code());
        document.put("name", prompt.name());
        document.put("system", prompt.system());
        document.put("user", prompt.user());
        document.put("updatedAt", prompt.updatedAt());
        documents.save(PROMPTS, prompt.id(), document);
        return prompt;
    }

    @Override
    public void deletePrompt(String id) {
        documents.delete(PROMPTS, id);
    }

    private static OfficePrompt toPrompt(Map<String, Object> document) {
        return new OfficePrompt(text(document, "id"), text(document, "code"), text(document, "name"),
                text(document, "system"), text(document, "user"), num(document, "updatedAt"));
    }

    // ---------- category ----------

    @Override
    public List<OfficeCategory> categories() {
        return all(CATEGORIES).stream().map(OfficeDocumentRepository::toCategory)
                .sorted(Comparator.comparingInt(OfficeCategory::sort)).toList();
    }

    @Override
    public Optional<OfficeCategory> category(String id) {
        return documents.findById(CATEGORIES, id).map(OfficeDocumentRepository::toCategory);
    }

    @Override
    public OfficeCategory saveCategory(OfficeCategory category) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", category.id());
        document.put("direction", category.direction());
        document.put("code", category.code());
        document.put("name", category.name());
        document.put("fields", new ArrayList<>(category.fields()));
        document.put("sort", category.sort());
        document.put("enabled", category.enabled());
        documents.save(CATEGORIES, category.id(), document);
        return category;
    }

    @Override
    public void deleteCategory(String id) {
        documents.delete(CATEGORIES, id);
    }

    private static OfficeCategory toCategory(Map<String, Object> document) {
        List<String> fields = new ArrayList<>();
        if (document.get("fields") instanceof List<?> raw) {
            raw.forEach(field -> fields.add(String.valueOf(field)));
        }
        return new OfficeCategory(text(document, "id"), text(document, "direction"), text(document, "code"),
                text(document, "name"), fields, (int) num(document, "sort"), bool(document, "enabled"));
    }

    // ---------- package ----------

    @Override
    public List<OfficePackage> packages() {
        return all(PACKAGES).stream().map(OfficeDocumentRepository::toPackage)
                .sorted((a, b) -> a.code().compareToIgnoreCase(b.code())).toList();
    }

    @Override
    public Optional<OfficePackage> aPackage(String id) {
        return documents.findById(PACKAGES, id).map(OfficeDocumentRepository::toPackage);
    }

    @Override
    public Optional<OfficePackage> packageByCode(String code) {
        return packages().stream().filter(aPackage -> aPackage.code().equals(code)).findFirst();
    }

    @Override
    public OfficePackage savePackage(OfficePackage aPackage) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", aPackage.id());
        document.put("code", aPackage.code());
        document.put("name", aPackage.name());
        document.put("dirPattern", aPackage.dirPattern());
        List<Map<String, Object>> items = new ArrayList<>();
        for (OfficePackage.Item item : aPackage.items()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("docTypeCode", item.docTypeCode());
            entry.put("target", item.target());
            entry.put("subDir", item.subDir());
            items.add(entry);
        }
        document.put("items", items);
        document.put("enabled", aPackage.enabled());
        document.put("updatedAt", aPackage.updatedAt());
        documents.save(PACKAGES, aPackage.id(), document);
        return aPackage;
    }

    @Override
    public void deletePackage(String id) {
        documents.delete(PACKAGES, id);
    }

    private static OfficePackage toPackage(Map<String, Object> document) {
        List<OfficePackage.Item> items = new ArrayList<>();
        if (document.get("items") instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof Map<?, ?> map) {
                    items.add(new OfficePackage.Item(text(map, "docTypeCode"), text(map, "target"),
                            text(map, "subDir")));
                }
            }
        }
        return new OfficePackage(text(document, "id"), text(document, "code"), text(document, "name"),
                text(document, "dirPattern"), items, bool(document, "enabled"), num(document, "updatedAt"));
    }

    // ---------- dataset record ----------

    @Override
    public List<OfficeDatasetRecord> datasetRecords(String schemaCode, int page, int size) {
        List<OfficeDatasetRecord> records = schemaCode == null || schemaCode.isBlank()
                ? toDatasetRecords(all(DATASET_RECORDS))
                : toDatasetRecords(documents.findByField(DATASET_RECORDS, "schemaCode", schemaCode,
                        1, Integer.MAX_VALUE));
        records.sort(Comparator.comparingLong(OfficeDatasetRecord::createdAt).reversed());
        return pageOf(records, page, size);
    }

    @Override
    public long countDatasetRecords(String schemaCode) {
        return schemaCode == null || schemaCode.isBlank()
                ? documents.count(DATASET_RECORDS)
                : documents.findByField(DATASET_RECORDS, "schemaCode", schemaCode, 1, Integer.MAX_VALUE).size();
    }

    @Override
    public Optional<OfficeDatasetRecord> datasetRecord(String id) {
        return documents.findById(DATASET_RECORDS, id).map(OfficeDocumentRepository::toDatasetRecord);
    }

    @Override
    public OfficeDatasetRecord saveDatasetRecord(OfficeDatasetRecord record) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", record.id());
        document.put("schemaCode", record.schemaCode());
        document.put("data", new LinkedHashMap<>(record.data()));
        document.put("attachments", fileRefDocuments(record.attachments()));
        document.put("createdBy", record.createdBy());
        document.put("createdAt", record.createdAt());
        document.put("updatedAt", record.updatedAt());
        documents.save(DATASET_RECORDS, record.id(), document);
        return record;
    }

    @Override
    public void deleteDatasetRecord(String id) {
        documents.delete(DATASET_RECORDS, id);
    }

    private List<OfficeDatasetRecord> toDatasetRecords(List<Map<String, Object>> documents) {
        List<OfficeDatasetRecord> records = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            records.add(toDatasetRecord(document));
        }
        return records;
    }

    private static OfficeDatasetRecord toDatasetRecord(Map<String, Object> document) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (document.get("data") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> data.put(String.valueOf(key), value));
        }
        return new OfficeDatasetRecord(text(document, "id"), text(document, "schemaCode"), data,
                fileRefs(document.get("attachments")), text(document, "createdBy"),
                num(document, "createdAt"), num(document, "updatedAt"));
    }

    // ---------- finance record ----------

    @Override
    public List<OfficeFinanceRecord> financeRecords(String direction, String status, String categoryCode,
                                                    String createdBy, int page, int size) {
        List<OfficeFinanceRecord> records = new ArrayList<>();
        for (OfficeFinanceRecord record : toFinanceRecords(all(FINANCE_RECORDS))) {
            if (direction != null && !direction.isBlank() && !direction.equals(record.direction())) {
                continue;
            }
            if (status != null && !status.isBlank() && !status.equals(record.status())) {
                continue;
            }
            if (categoryCode != null && !categoryCode.isBlank() && !categoryCode.equals(record.categoryCode())) {
                continue;
            }
            if (createdBy != null && !createdBy.isBlank() && !createdBy.equals(record.createdBy())) {
                continue;
            }
            records.add(record);
        }
        records.sort(Comparator.comparingLong(OfficeFinanceRecord::occurredAt).reversed());
        return pageOf(records, page, size);
    }

    @Override
    public long countFinanceRecords(String direction, String status, String categoryCode, String createdBy) {
        return financeRecords(direction, status, categoryCode, createdBy, 1, Integer.MAX_VALUE).size();
    }

    @Override
    public List<OfficeFinanceRecord> financeRecordsByPeriod(long fromInclusive, long toInclusive) {
        List<OfficeFinanceRecord> records = new ArrayList<>();
        for (OfficeFinanceRecord record : toFinanceRecords(all(FINANCE_RECORDS))) {
            if (record.occurredAt() >= fromInclusive && record.occurredAt() <= toInclusive) {
                records.add(record);
            }
        }
        records.sort(Comparator.comparingLong(OfficeFinanceRecord::occurredAt));
        return records;
    }

    @Override
    public Optional<OfficeFinanceRecord> financeRecord(String id) {
        return documents.findById(FINANCE_RECORDS, id).map(OfficeDocumentRepository::toFinanceRecord);
    }

    @Override
    public OfficeFinanceRecord saveFinanceRecord(OfficeFinanceRecord record) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", record.id());
        document.put("direction", record.direction());
        document.put("categoryCode", record.categoryCode());
        document.put("subCategory", record.subCategory());
        document.put("title", record.title());
        document.put("amountCents", record.amountCents());
        document.put("quantity", record.quantity());
        document.put("unitPriceCents", record.unitPriceCents());
        document.put("buyerId", record.buyerId());
        document.put("buyerName", record.buyerName());
        document.put("occurredAt", record.occurredAt());
        document.put("purpose", record.purpose());
        document.put("fundSource", record.fundSource());
        document.put("attachments", fileRefDocuments(record.attachments()));
        document.put("status", record.status());
        document.put("reviewedBy", record.reviewedBy());
        document.put("reviewedAt", record.reviewedAt());
        document.put("reviewNote", record.reviewNote());
        document.put("settlementBatchId", record.settlementBatchId());
        document.put("createdBy", record.createdBy());
        document.put("createdAt", record.createdAt());
        documents.save(FINANCE_RECORDS, record.id(), document);
        return record;
    }

    @Override
    public void deleteFinanceRecord(String id) {
        documents.delete(FINANCE_RECORDS, id);
    }

    private List<OfficeFinanceRecord> toFinanceRecords(List<Map<String, Object>> documents) {
        List<OfficeFinanceRecord> records = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            records.add(toFinanceRecord(document));
        }
        return records;
    }

    private static OfficeFinanceRecord toFinanceRecord(Map<String, Object> document) {
        return new OfficeFinanceRecord(text(document, "id"), text(document, "direction"),
                text(document, "categoryCode"), text(document, "subCategory"), text(document, "title"),
                num(document, "amountCents"), num(document, "quantity"), num(document, "unitPriceCents"),
                text(document, "buyerId"), text(document, "buyerName"), num(document, "occurredAt"),
                text(document, "purpose"), text(document, "fundSource"),
                fileRefs(document.get("attachments")), text(document, "status"), text(document, "reviewedBy"),
                num(document, "reviewedAt"), text(document, "reviewNote"), text(document, "settlementBatchId"),
                text(document, "createdBy"), num(document, "createdAt"));
    }

    private static List<Map<String, Object>> fileRefDocuments(List<OfficeFinanceRecord.FileRef> refs) {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (OfficeFinanceRecord.FileRef ref : refs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileKey", ref.fileKey());
            item.put("filename", ref.filename());
            item.put("contentType", ref.contentType());
            item.put("size", ref.size());
            item.put("kind", ref.kind());
            documents.add(item);
        }
        return documents;
    }

    private static List<OfficeFinanceRecord.FileRef> fileRefs(Object raw) {
        List<OfficeFinanceRecord.FileRef> refs = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    refs.add(new OfficeFinanceRecord.FileRef(text(map, "fileKey"), text(map, "filename"),
                            text(map, "contentType"), num(map, "size"), text(map, "kind")));
                }
            }
        }
        return refs;
    }

    // ---------- settlement batch ----------

    @Override
    public List<OfficeSettlementBatch> settlementBatches() {
        return all(SETTLEMENT_BATCHES).stream().map(OfficeDocumentRepository::toBatch)
                .sorted((a, b) -> b.period().compareTo(a.period())).toList();
    }

    @Override
    public Optional<OfficeSettlementBatch> settlementBatch(String period) {
        return documents.findById(SETTLEMENT_BATCHES, batchId(period)).map(OfficeDocumentRepository::toBatch);
    }

    @Override
    public OfficeSettlementBatch saveSettlementBatch(OfficeSettlementBatch batch) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", batch.id());
        document.put("period", batch.period());
        document.put("status", batch.status());
        document.put("incomeTotal", batch.incomeTotal());
        document.put("expenseTotal", batch.expenseTotal());
        document.put("balanceCarryIn", batch.balanceCarryIn());
        document.put("balanceCarryOut", batch.balanceCarryOut());
        document.put("closedAt", batch.closedAt());
        document.put("closedBy", batch.closedBy());
        documents.save(SETTLEMENT_BATCHES, batch.id(), document);
        return batch;
    }

    /** 批次以周期为 id，天然幂等。 */
    public static String batchId(String period) {
        return "batch-" + period;
    }

    private static OfficeSettlementBatch toBatch(Map<String, Object> document) {
        return new OfficeSettlementBatch(text(document, "id"), text(document, "period"),
                text(document, "status"), num(document, "incomeTotal"), num(document, "expenseTotal"),
                num(document, "balanceCarryIn"), num(document, "balanceCarryOut"), num(document, "closedAt"),
                text(document, "closedBy"));
    }

    // ---------- doc job ----------

    @Override
    public List<OfficeDocJob> docJobs(String docTypeCode, String status, int page, int size) {
        List<OfficeDocJob> jobs = new ArrayList<>();
        for (OfficeDocJob job : toDocJobs(all(DOC_JOBS))) {
            if (docTypeCode != null && !docTypeCode.isBlank() && !docTypeCode.equals(job.docTypeCode())) {
                continue;
            }
            if (status != null && !status.isBlank() && !status.equals(job.status())) {
                continue;
            }
            jobs.add(job);
        }
        jobs.sort(Comparator.comparingLong(OfficeDocJob::updatedAt).reversed());
        return pageOf(jobs, page, size);
    }

    @Override
    public long countDocJobs(String docTypeCode, String status) {
        return docJobs(docTypeCode, status, 1, Integer.MAX_VALUE).size();
    }

    @Override
    public List<OfficeDocJob> docJobsByTarget(String docTypeCode, String sourceType, String sourceId,
                                              String period) {
        List<OfficeDocJob> jobs = new ArrayList<>();
        for (OfficeDocJob job : toDocJobs(documents.findByField(DOC_JOBS, "docTypeCode", docTypeCode,
                1, Integer.MAX_VALUE))) {
            if (!job.sourceType().equals(sourceType)) {
                continue;
            }
            if (sourceId != null && !sourceId.equals(job.sourceId())) {
                continue;
            }
            if (period != null && !period.isBlank() && !period.equals(job.period())) {
                continue;
            }
            jobs.add(job);
        }
        jobs.sort(Comparator.comparingLong(OfficeDocJob::updatedAt).reversed());
        return jobs;
    }

    @Override
    public Optional<OfficeDocJob> docJob(String id) {
        return documents.findById(DOC_JOBS, id).map(OfficeDocumentRepository::toDocJob);
    }

    @Override
    public OfficeDocJob saveDocJob(OfficeDocJob job) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", job.id());
        document.put("docTypeCode", job.docTypeCode());
        document.put("sourceType", job.sourceType());
        document.put("sourceId", job.sourceId());
        document.put("period", job.period());
        document.put("status", job.status());
        document.put("draft", new LinkedHashMap<>(job.draft()));
        document.put("aiRuns", new ArrayList<>(job.aiRuns()));
        document.put("resultFileKey", job.resultFileKey());
        document.put("resultFilename", job.resultFilename());
        document.put("error", job.error());
        document.put("createdBy", job.createdBy());
        document.put("createdAt", job.createdAt());
        document.put("updatedAt", job.updatedAt());
        documents.save(DOC_JOBS, job.id(), document);
        return job;
    }

    @Override
    public void deleteDocJob(String id) {
        documents.delete(DOC_JOBS, id);
    }

    private List<OfficeDocJob> toDocJobs(List<Map<String, Object>> documents) {
        List<OfficeDocJob> jobs = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            jobs.add(toDocJob(document));
        }
        return jobs;
    }

    private static OfficeDocJob toDocJob(Map<String, Object> document) {
        Map<String, String> draft = new LinkedHashMap<>();
        if (document.get("draft") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> draft.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
        }
        List<String> runs = new ArrayList<>();
        if (document.get("aiRuns") instanceof List<?> rawRuns) {
            rawRuns.forEach(run -> runs.add(String.valueOf(run)));
        }
        return new OfficeDocJob(text(document, "id"), text(document, "docTypeCode"), text(document, "sourceType"),
                text(document, "sourceId"), text(document, "period"), text(document, "status"), draft, runs,
                text(document, "resultFileKey"), text(document, "resultFilename"), text(document, "error"),
                text(document, "createdBy"), num(document, "createdAt"), num(document, "updatedAt"));
    }

    // ---------- 通用 ----------

    private List<Map<String, Object>> all(String collection) {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (int page = 1; ; page++) {
            List<Map<String, Object>> batch = this.documents.findAll(collection, page, 200);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            documents.addAll(batch);
            if (batch.size() < 200) {
                break;
            }
        }
        return documents;
    }

    private static <T> List<T> pageOf(List<T> records, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(Math.min(size <= 0 ? 20 : size, 200), 1);
        int from = Math.min((safePage - 1) * safeSize, records.size());
        int to = Math.min(from + safeSize, records.size());
        return records.subList(from, to);
    }

    private static String text(Map<?, ?> document, String key) {
        Object value = document.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static long num(Map<?, ?> document, String key) {
        Object value = document.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean bool(Map<?, ?> document, String key) {
        Object value = document.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
