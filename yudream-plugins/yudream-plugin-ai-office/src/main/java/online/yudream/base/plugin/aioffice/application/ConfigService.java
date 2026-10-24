package online.yudream.base.plugin.aioffice.application;

import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeCategory;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDatasetRecord;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocType;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeFinanceRecord;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficePackage;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficePrompt;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeSchema;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeSettings;
import online.yudream.base.plugin.aioffice.domain.repo.OfficeRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 站点配置维护：设置、数据模型、文档类型、提示词、类别字典、打包定义、数据集记录。 */
public class ConfigService {

    private final OfficeRepository repository;

    public ConfigService(OfficeRepository repository) {
        this.repository = repository;
    }

    // ---------- settings ----------

    public OfficeSettings settings() {
        return repository.settings();
    }

    public OfficeSettings updateSettings(Map<String, Object> body) {
        OfficeSettings current = repository.settings();
        String profileSchema = str(body.get("profileSchemaCode"), current.profileSchemaCode());
        String cycle = str(body.get("financeCycle"), current.financeCycle());
        String currency = str(body.get("currency"), current.currency());
        String styleGuide = str(body.get("styleGuide"), current.styleGuide());
        String provider = str(body.get("aiProviderCode"), current.aiProviderCode());
        String model = str(body.get("aiModelCode"), current.aiModelCode());
        Map<String, Object> profile = current.profile();
        if (body.containsKey("profile") && body.get("profile") instanceof Map<?, ?> raw) {
            Map<String, Object> rebuilt = new LinkedHashMap<>();
            raw.forEach((key, value) -> rebuilt.put(String.valueOf(key), value));
            profile = rebuilt;
        }
        return repository.saveSettings(current.withMeta(profileSchema, cycle, currency, styleGuide, provider, model)
                .withImages(body.containsKey("wordImageEmbed")
                        ? Boolean.parseBoolean(String.valueOf(body.get("wordImageEmbed")))
                        : current.wordImageEmbed())
                .withProfile(profile));
    }

    // ---------- schema ----------

    public List<OfficeSchema> schemas() {
        return repository.schemas();
    }

    public OfficeSchema saveSchema(OfficeSchema schema) {
        repository.schemaByCode(schema.code())
                .filter(existing -> !existing.id().equals(schema.id()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("schema 编码已存在：" + schema.code());
                });
        return repository.saveSchema(schema);
    }

    public void deleteSchema(String id) {
        OfficeSchema schema = repository.schema(id)
                .orElseThrow(() -> new IllegalArgumentException("数据模型不存在"));
        if (schema.builtin()) {
            throw new IllegalArgumentException("内置数据模型不可删除");
        }
        repository.deleteSchema(id);
    }

    // ---------- doc type ----------

    public List<OfficeDocType> docTypes() {
        return repository.docTypes();
    }

    public OfficeDocType saveDocType(OfficeDocType docType) {
        repository.docTypeByCode(docType.code())
                .filter(existing -> !existing.id().equals(docType.id()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("文档类型编码已存在：" + docType.code());
                });
        return repository.saveDocType(docType);
    }

    public void deleteDocType(String id) {
        repository.deleteDocType(id);
    }

    // ---------- prompt ----------

    public List<OfficePrompt> prompts() {
        return repository.prompts();
    }

    public OfficePrompt savePrompt(OfficePrompt prompt) {
        repository.promptByCode(prompt.code())
                .filter(existing -> !existing.id().equals(prompt.id()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("提示词编码已存在：" + prompt.code());
                });
        return repository.savePrompt(prompt);
    }

    public void deletePrompt(String id) {
        repository.deletePrompt(id);
    }

    // ---------- category ----------

    public List<OfficeCategory> categories() {
        return repository.categories();
    }

    /** 启用中的类别（用户面报销登记用，不含维护字段）。 */
    public List<OfficeCategory> activeCategories(String direction) {
        return repository.categories().stream()
                .filter(OfficeCategory::enabled)
                .filter(category -> direction == null || direction.isBlank()
                        || category.direction().equals(direction))
                .toList();
    }

    public OfficeCategory saveCategory(OfficeCategory category) {
        boolean duplicated = repository.categories().stream()
                .anyMatch(existing -> !existing.id().equals(category.id())
                        && existing.direction().equals(category.direction())
                        && existing.code().equals(category.code()));
        if (duplicated) {
            throw new IllegalArgumentException("同方向下类别编码已存在：" + category.code());
        }
        return repository.saveCategory(category);
    }

    public void deleteCategory(String id) {
        repository.deleteCategory(id);
    }

    // ---------- package ----------

    public List<OfficePackage> packages() {
        return repository.packages();
    }

    public OfficePackage savePackage(OfficePackage aPackage) {
        repository.packageByCode(aPackage.code())
                .filter(existing -> !existing.id().equals(aPackage.id()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("打包定义编码已存在：" + aPackage.code());
                });
        return repository.savePackage(aPackage);
    }

    public void deletePackage(String id) {
        repository.deletePackage(id);
    }

    // ---------- dataset record ----------

    public List<OfficeDatasetRecord> datasetRecords(String schemaCode, int page, int size) {
        return repository.datasetRecords(schemaCode, page, size);
    }

    public long countDatasetRecords(String schemaCode) {
        return repository.countDatasetRecords(schemaCode);
    }

    public Optional<OfficeDatasetRecord> datasetRecord(String id) {
        return repository.datasetRecord(id);
    }

    public OfficeDatasetRecord saveDatasetRecord(OfficeDatasetRecord record) {
        repository.schemaByCode(record.schemaCode())
                .orElseThrow(() -> new IllegalArgumentException("数据模型不存在：" + record.schemaCode()));
        return repository.saveDatasetRecord(record);
    }

    public void deleteDatasetRecord(String id) {
        repository.deleteDatasetRecord(id);
    }

    private static String str(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }
}
