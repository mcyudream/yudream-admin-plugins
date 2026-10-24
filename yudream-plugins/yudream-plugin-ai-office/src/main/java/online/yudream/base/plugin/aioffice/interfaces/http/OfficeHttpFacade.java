package online.yudream.base.plugin.aioffice.interfaces.http;

import online.yudream.base.plugin.aioffice.application.ConfigService;
import online.yudream.base.plugin.aioffice.application.FinanceService;
import online.yudream.base.plugin.aioffice.application.GenerationService;
import online.yudream.base.plugin.aioffice.application.PresetPackService;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeCategory;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDatasetRecord;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocJob;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocType;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeFinanceRecord;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficePackage;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficePrompt;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeSchema;
import online.yudream.base.plugin.aioffice.interfaces.support.JsonSupport;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.http.PluginHttpPart;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiProviderOption;
import online.yudream.base.plugin.spi.system.document.PluginWordTemplateService;
import online.yudream.base.plugin.spi.system.document.PluginWordTemplateSummary;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** AI 模板办公 HTTP 装配层：控制器薄壳之后的全部请求处理。 */
public class OfficeHttpFacade {

    private final ConfigService config;
    private final FinanceService finance;
    private final GenerationService generation;
    private final PresetPackService presets;
    private final PluginContext context;

    public OfficeHttpFacade(ConfigService config, FinanceService finance, GenerationService generation,
                            PresetPackService presets, PluginContext context) {
        this.config = config;
        this.finance = finance;
        this.generation = generation;
        this.presets = presets;
        this.context = context;
    }

    // ---------- 设置 ----------

    public PluginHttpResponse settings(PluginHttpRequest request) {
        return guard(() -> {
            if ("PUT".equals(request.method()) || "POST".equals(request.method())) {
                return PluginHttpResponse.ok(config.updateSettings(JsonSupport.readMap(request.body())));
            }
            return PluginHttpResponse.ok(config.settings());
        });
    }

    // ---------- schema ----------

    public PluginHttpResponse schemas(PluginHttpRequest request) {
        return guard(() -> "POST".equals(request.method())
                ? PluginHttpResponse.ok(config.saveSchema(withId(JsonSupport.read(request.body(), OfficeSchema.class))))
                : PluginHttpResponse.ok(config.schemas()));
    }

    public PluginHttpResponse schemaItem(PluginHttpRequest request, String id) {
        return guard(() -> {
            if ("PUT".equals(request.method())) {
                OfficeSchema schema = JsonSupport.read(request.body(), OfficeSchema.class);
                return PluginHttpResponse.ok(config.saveSchema(
                        new OfficeSchema(id, schema.code(), schema.name(), schema.role(), schema.builtin(),
                                schema.fields(), System.currentTimeMillis())));
            }
            config.deleteSchema(id);
            return PluginHttpResponse.ok(Map.of("ok", true));
        });
    }

    // ---------- doc type ----------

    public PluginHttpResponse docTypes(PluginHttpRequest request) {
        return guard(() -> "POST".equals(request.method())
                ? PluginHttpResponse.ok(config.saveDocType(withId(JsonSupport.read(request.body(), OfficeDocType.class))))
                : PluginHttpResponse.ok(config.docTypes()));
    }

    public PluginHttpResponse docTypeItem(PluginHttpRequest request, String id) {
        return guard(() -> {
            if ("PUT".equals(request.method())) {
                OfficeDocType docType = JsonSupport.read(request.body(), OfficeDocType.class);
                return PluginHttpResponse.ok(config.saveDocType(new OfficeDocType(id, docType.code(), docType.name(),
                        docType.stage(), docType.wordTemplateCode(), docType.schemaCodes(), docType.mappings(),
                        docType.fileNamePattern(), docType.enabled(), System.currentTimeMillis())));
            }
            config.deleteDocType(id);
            return PluginHttpResponse.ok(Map.of("ok", true));
        });
    }

    // ---------- prompt ----------

    public PluginHttpResponse prompts(PluginHttpRequest request) {
        return guard(() -> "POST".equals(request.method())
                ? PluginHttpResponse.ok(config.savePrompt(withId(JsonSupport.read(request.body(), OfficePrompt.class))))
                : PluginHttpResponse.ok(config.prompts()));
    }

    public PluginHttpResponse promptItem(PluginHttpRequest request, String id) {
        return guard(() -> {
            if ("PUT".equals(request.method())) {
                OfficePrompt prompt = JsonSupport.read(request.body(), OfficePrompt.class);
                return PluginHttpResponse.ok(config.savePrompt(new OfficePrompt(id, prompt.code(), prompt.name(),
                        prompt.system(), prompt.user(), System.currentTimeMillis())));
            }
            config.deletePrompt(id);
            return PluginHttpResponse.ok(Map.of("ok", true));
        });
    }

    // ---------- category ----------

    public PluginHttpResponse categories(PluginHttpRequest request) {
        return guard(() -> "POST".equals(request.method())
                ? PluginHttpResponse.ok(config.saveCategory(withId(JsonSupport.read(request.body(), OfficeCategory.class))))
                : PluginHttpResponse.ok(config.categories()));
    }

    public PluginHttpResponse categoryItem(PluginHttpRequest request, String id) {
        return guard(() -> {
            if ("PUT".equals(request.method())) {
                OfficeCategory category = JsonSupport.read(request.body(), OfficeCategory.class);
                return PluginHttpResponse.ok(config.saveCategory(new OfficeCategory(id, category.direction(),
                        category.code(), category.name(), category.fields(), category.schemaCode(),
                        category.sort(), category.enabled())));
            }
            config.deleteCategory(id);
            return PluginHttpResponse.ok(Map.of("ok", true));
        });
    }

    // ---------- package ----------

    public PluginHttpResponse packages(PluginHttpRequest request) {
        return guard(() -> "POST".equals(request.method())
                ? PluginHttpResponse.ok(config.savePackage(withId(JsonSupport.read(request.body(), OfficePackage.class))))
                : PluginHttpResponse.ok(config.packages()));
    }

    public PluginHttpResponse packageItem(PluginHttpRequest request, String id) {
        return guard(() -> {
            if ("PUT".equals(request.method())) {
                OfficePackage aPackage = JsonSupport.read(request.body(), OfficePackage.class);
                return PluginHttpResponse.ok(config.savePackage(new OfficePackage(id, aPackage.code(),
                        aPackage.name(), aPackage.dirPattern(), aPackage.items(), aPackage.enabled(),
                        System.currentTimeMillis())));
            }
            config.deletePackage(id);
            return PluginHttpResponse.ok(Map.of("ok", true));
        });
    }

    // ---------- dataset ----------

    public PluginHttpResponse dataset(PluginHttpRequest request) {
        return guard(() -> {
            if ("POST".equals(request.method())) {
                OfficeDatasetRecord record = JsonSupport.read(request.body(), OfficeDatasetRecord.class);
                OfficeDatasetRecord withUser = new OfficeDatasetRecord(
                        record.id() == null || record.id().isBlank() ? UUID.randomUUID().toString() : record.id(),
                        record.schemaCode(), record.data(), record.attachments(),
                        currentUserId(request), System.currentTimeMillis(), System.currentTimeMillis());
                return PluginHttpResponse.ok(config.saveDatasetRecord(withUser));
            }
            String schemaCode = firstQuery(request, "schemaCode");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("records", config.datasetRecords(schemaCode, page(request), size(request)));
            result.put("total", config.countDatasetRecords(schemaCode));
            return PluginHttpResponse.ok(result);
        });
    }

    public PluginHttpResponse datasetItem(PluginHttpRequest request, String id) {
        return guard(() -> {
            if ("PUT".equals(request.method())) {
                OfficeDatasetRecord existing = config.datasetRecord(id)
                        .orElseThrow(() -> new IllegalArgumentException("数据记录不存在"));
                OfficeDatasetRecord body = JsonSupport.read(request.body(), OfficeDatasetRecord.class);
                return PluginHttpResponse.ok(config.saveDatasetRecord(
                        existing.withData(body.data()).withAttachments(body.attachments())));
            }
            config.deleteDatasetRecord(id);
            return PluginHttpResponse.ok(Map.of("ok", true));
        });
    }

    // ---------- 平台能力查询 ----------

    public PluginHttpResponse wordTemplates(PluginHttpRequest request) {
        return guard(() -> {
            try {
                PluginWordTemplateService wordTemplates = context.framework().wordTemplates();
                List<Map<String, Object>> list = new ArrayList<>();
                for (PluginWordTemplateSummary template : wordTemplates.templates(null, 1, 100)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", template.code());
                    item.put("name", template.name());
                    item.put("originalFilename", template.originalFilename());
                    list.add(item);
                }
                return PluginHttpResponse.ok(list);
            } catch (LinkageError | RuntimeException e) {
                throw new IllegalArgumentException("宿主 Word 模板能力未启用（平台能力 document-template）");
            }
        });
    }

    public PluginHttpResponse aiProviders(PluginHttpRequest request) {
        return guard(() -> {
            try {
                List<PluginAiProviderOption> providers = context.framework().ai().providers();
                return PluginHttpResponse.ok(providers);
            } catch (LinkageError | RuntimeException e) {
                return PluginHttpResponse.ok(List.of());
            }
        });
    }

    // ---------- 预设包 ----------

    public PluginHttpResponse presetList(PluginHttpRequest request) {
        return guard(() -> PluginHttpResponse.ok(presets.builtins()));
    }

    public PluginHttpResponse presetImport(PluginHttpRequest request) {
        return guard(() -> {
            Map<String, Object> body = JsonSupport.readMap(request.body());
            String code = String.valueOf(body.getOrDefault("code", ""));
            boolean overwrite = Boolean.parseBoolean(String.valueOf(body.getOrDefault("overwrite", "false")));
            return PluginHttpResponse.ok(presets.importBuiltin(code, overwrite));
        });
    }

    public PluginHttpResponse presetUpload(PluginHttpRequest request) {
        return guard(() -> {
            PluginHttpPart file = request.parts() == null ? null : request.parts().get("file");
            String json;
            if (file != null && file.isFile()) {
                json = new String(file.data(), StandardCharsets.UTF_8);
            } else {
                // 平台文件桥：JSON {fileId}
                Map<String, Object> body = JsonSupport.readMap(request.body());
                String fileId = String.valueOf(body.getOrDefault("fileId", ""));
                if (fileId.isBlank()) {
                    throw new IllegalArgumentException("请上传预设 JSON 文件");
                }
                online.yudream.base.plugin.spi.system.storage.PluginStoredFile platform = context.framework()
                        .platformFile(fileId)
                        .orElseThrow(() -> new IllegalArgumentException("平台文件不存在或已被清理，请重新上传"));
                try (InputStream input = platform.inputStream()) {
                    json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new IllegalStateException("预设文件读取失败：" + e.getMessage(), e);
                }
            }
            boolean overwrite = "true".equalsIgnoreCase(firstQuery(request, "overwrite"));
            return PluginHttpResponse.ok(presets.importContent(json, overwrite));
        });
    }

    public PluginHttpResponse presetExport(PluginHttpRequest request) {
        return guard(() -> {
            byte[] content = JsonSupport.write(presets.exportCurrent())
                    .getBytes(StandardCharsets.UTF_8);
            return new PluginHttpResponse(200,
                    Map.of("Content-Disposition", "attachment; filename*=UTF-8''"
                            + URLEncoder.encode("ai-office-preset.json", StandardCharsets.UTF_8)),
                    "application/json", content, false);
        });
    }

    // ---------- 财务 ----------

    public PluginHttpResponse myFinance(PluginHttpRequest request) {
        return guard(() -> {
            if ("POST".equals(request.method())) {
                OfficeFinanceRecord record = JsonSupport.read(request.body(), OfficeFinanceRecord.class);
                return PluginHttpResponse.ok(finance.create(withFinanceUser(record, currentUserId(request),
                        currentUserName(request)), false));
            }
            String userId = currentUserId(request);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("records", finance.records(null, null, null, userId, page(request), size(request)));
            result.put("total", finance.countRecords(null, null, null, userId));
            return PluginHttpResponse.ok(result);
        });
    }

    public PluginHttpResponse myFinanceUpload(PluginHttpRequest request) {
        return guard(() -> {
            PluginHttpPart file = request.parts() == null ? null : request.parts().get("file");
            if (file != null && file.isFile()) {
                return PluginHttpResponse.ok(finance.uploadAttachment(file.filename(), file.contentType(),
                        strOrNullQuery(request, "kind"), file.data()));
            }
            Map<String, Object> body = JsonSupport.readMap(request.body());
            return PluginHttpResponse.ok(finance.uploadPlatformFile(
                    String.valueOf(body.getOrDefault("fileId", "")),
                    body.get("filename") == null ? null : String.valueOf(body.get("filename")),
                    strOrNullQuery(request, "kind")));
        });
    }

    /** 用户面可用的启用类别（报销登记下拉），只读不暴露维护面。 */
    public PluginHttpResponse myCategories(PluginHttpRequest request) {
        return guard(() -> PluginHttpResponse.ok(config.activeCategories(firstQuery(request, "direction"))));
    }

    /** 凭证类型字典（站点设置的数据驱动字典，供上传时下拉选择）。 */
    public PluginHttpResponse myCredentialKinds(PluginHttpRequest request) {
        return guard(() -> PluginHttpResponse.ok(config.settings().credentialKinds()));
    }

    public PluginHttpResponse myFinanceItem(PluginHttpRequest request, String id) {
        return guard(() -> {
            finance.requireOwned(id, currentUserId(request));
            finance.delete(id);
            return PluginHttpResponse.ok(Map.of("ok", true));
        });
    }

    /** 下载本人记录的凭证附件（index 从 0 开始）。 */
    public PluginHttpResponse myFinanceAttachment(PluginHttpRequest request, String id, int index) {
        return guard(() -> {
            OfficeFinanceRecord record = finance.requireOwned(id, currentUserId(request));
            if (index < 0 || index >= record.attachments().size()) {
                throw new IllegalArgumentException("附件不存在");
            }
            OfficeFinanceRecord.FileRef attachment = record.attachments().get(index);
            return fileResponse(attachment.fileKey(),
                    attachment.filename().isBlank() ? "attachment" : attachment.filename(),
                    attachment.contentType().isBlank() ? "application/octet-stream" : attachment.contentType());
        });
    }

    public PluginHttpResponse adminFinance(PluginHttpRequest request) {
        return guard(() -> {
            if ("POST".equals(request.method())) {
                OfficeFinanceRecord record = JsonSupport.read(request.body(), OfficeFinanceRecord.class);
                boolean acceptNow = Boolean.parseBoolean(firstQuery(request, "accept"));
                return PluginHttpResponse.ok(finance.create(
                        withFinanceUser(record, currentUserId(request), currentUserName(request)), acceptNow));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("records", finance.records(firstQuery(request, "direction"), firstQuery(request, "status"),
                    firstQuery(request, "categoryCode"), null, page(request), size(request)));
            result.put("total", finance.countRecords(firstQuery(request, "direction"),
                    firstQuery(request, "status"), firstQuery(request, "categoryCode"), null));
            return PluginHttpResponse.ok(result);
        });
    }

    public PluginHttpResponse adminFinanceReview(PluginHttpRequest request, String id) {
        return guard(() -> {
            ReviewRequest body = JsonSupport.read(request.body(), ReviewRequest.class);
            return PluginHttpResponse.ok(finance.review(id, currentUserId(request), body.accept(), body.note()));
        });
    }

    public PluginHttpResponse adminFinanceItem(PluginHttpRequest request, String id) {
        return guard(() -> {
            finance.delete(id);
            return PluginHttpResponse.ok(Map.of("ok", true));
        });
    }

    public PluginHttpResponse settlements(PluginHttpRequest request) {
        return guard(() -> PluginHttpResponse.ok(finance.batches()));
    }

    public PluginHttpResponse settlementOverview(PluginHttpRequest request) {
        return guard(() -> PluginHttpResponse.ok(finance.overview(
                orDefault(firstQuery(request, "period"), FinanceService.currentPeriod()))));
    }

    public PluginHttpResponse settlementClose(PluginHttpRequest request) {
        return guard(() -> {
            Map<String, Object> body = JsonSupport.readMap(request.body());
            String period = String.valueOf(body.getOrDefault("period", ""));
            return PluginHttpResponse.ok(finance.close(period, currentUserId(request)));
        });
    }

    // ---------- 生成 ----------

    public PluginHttpResponse generateContext(PluginHttpRequest request) {
        return guard(() -> PluginHttpResponse.ok(generation.context(
                orDefault(firstQuery(request, "sourceType"), "MONTH"),
                orDefault(firstQuery(request, "sourceId"), ""),
                orDefault(firstQuery(request, "period"), ""))));
    }

    public PluginHttpResponse generatePreview(PluginHttpRequest request) {
        return guard(() -> {
            PreviewRequest body = JsonSupport.read(request.body(), PreviewRequest.class);
            return PluginHttpResponse.ok(generation.preview(body.docTypeCode(), body.sourceType(),
                    body.sourceId(), body.period()));
        });
    }

    public PluginHttpResponse generateJobs(PluginHttpRequest request) {
        return guard(() -> {
            if ("POST".equals(request.method())) {
                CreateJobRequest body = JsonSupport.read(request.body(), CreateJobRequest.class);
                return PluginHttpResponse.ok(generation.createJob(body.docTypeCode(), body.sourceType(),
                        body.sourceId(), body.period(), currentUserId(request)));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("records", generation.jobs(firstQuery(request, "docTypeCode"),
                    firstQuery(request, "status"), page(request), size(request)));
            result.put("total", generation.countJobs(firstQuery(request, "docTypeCode"),
                    firstQuery(request, "status")));
            return PluginHttpResponse.ok(result);
        });
    }

    public PluginHttpResponse generateJobItem(PluginHttpRequest request, String id) {
        return guard(() -> {
            if ("DELETE".equals(request.method())) {
                generation.deleteJob(id);
                return PluginHttpResponse.ok(Map.of("ok", true));
            }
            return PluginHttpResponse.ok(generation.job(id));
        });
    }

    public PluginHttpResponse generateJobAi(PluginHttpRequest request, String id) {
        return guard(() -> PluginHttpResponse.ok(generation.runAi(id, currentUserId(request))));
    }

    public PluginHttpResponse generateJobDraft(PluginHttpRequest request, String id) {
        return guard(() -> PluginHttpResponse.ok(
                generation.saveDraft(id, JsonSupport.readMap(request.body()).get("draft") instanceof Map<?, ?> draft
                        ? toStringMap(draft) : Map.of())));
    }

    public PluginHttpResponse generateJobRender(PluginHttpRequest request, String id) {
        return guard(() -> PluginHttpResponse.ok(generation.render(id)));
    }

    public PluginHttpResponse downloadJob(PluginHttpRequest request, String id) {
        return guard(() -> {
            OfficeDocJob job = generation.job(id);
            if (job.resultFileKey() == null || job.resultFileKey().isBlank()) {
                throw new IllegalArgumentException("该任务尚无定稿产物");
            }
            return fileResponse(job.resultFileKey(), job.resultFilename(),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        });
    }

    public PluginHttpResponse packagePrecheck(PluginHttpRequest request, String code) {
        return guard(() -> PluginHttpResponse.ok(generation.precheck(code,
                orDefault(firstQuery(request, "period"), FinanceService.currentPeriod()))));
    }

    public PluginHttpResponse packageExport(PluginHttpRequest request, String code) {
        return guard(() -> {
            String period = orDefault(firstQuery(request, "period"), FinanceService.currentPeriod());
            boolean skipMissing = "true".equalsIgnoreCase(firstQuery(request, "skipMissing"));
            byte[] zip = generation.exportPackage(code, period, skipMissing, currentUserId(request));
            String filename = "ai-office-" + code + "-" + period + ".zip";
            return new PluginHttpResponse(200,
                    Map.of("Content-Disposition", "attachment; filename*=UTF-8''"
                            + URLEncoder.encode(filename, StandardCharsets.UTF_8)),
                    "application/zip", zip, false);
        });
    }

    // ---------- 基础工具 ----------

    private static Map<String, String> toStringMap(Map<?, ?> raw) {
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
        return result;
    }

    private OfficeFinanceRecord withFinanceUser(OfficeFinanceRecord record, String userId, String userName) {
        return new OfficeFinanceRecord(
                record.id() == null || record.id().isBlank() ? UUID.randomUUID().toString() : record.id(),
                record.direction(), record.categoryCode(), record.subCategory(), record.title(),
                record.amountCents(), record.quantity(), record.unitPriceCents(),
                record.buyerId() == null || record.buyerId().isBlank() ? userId : record.buyerId(),
                record.buyerName() == null || record.buyerName().isBlank() ? userName : record.buyerName(),
                record.occurredAt() > 0 ? record.occurredAt() : System.currentTimeMillis(),
                record.purpose(), record.fundSource(), record.data(), record.attachments(), record.status(),
                record.reviewedBy(), record.reviewedAt(), record.reviewNote(), record.settlementBatchId(),
                userId, System.currentTimeMillis());
    }

    private PluginHttpResponse fileResponse(String objectKey, String filename, String contentType) {
        try (InputStream stream = context.files().get(objectKey).inputStream()) {
            return new PluginHttpResponse(200,
                    Map.of("Content-Disposition", "attachment; filename*=UTF-8''"
                            + URLEncoder.encode(filename, StandardCharsets.UTF_8), "Cache-Control", "no-cache"),
                    contentType, stream.readAllBytes(), false);
        } catch (Exception e) {
            return PluginHttpResponse.rawJson(404, Map.of("message", "文件不存在或已被清理"));
        }
    }

    public static String currentUserId(PluginHttpRequest request) {
        return request.principal() == null || request.principal().userId() == null
                ? "" : String.valueOf(request.principal().userId());
    }

    private String currentUserName(PluginHttpRequest request) {
        // principal 只有 userId，名称留空由前端用户选择器补全
        return "";
    }

    private static int page(PluginHttpRequest request) {
        return intQuery(request, "page", 1);
    }

    private static int size(PluginHttpRequest request) {
        return intQuery(request, "size", 20);
    }

    private static int intQuery(PluginHttpRequest request, String key, int defaultValue) {
        List<String> values = request.query().get(key);
        if (values == null || values.isEmpty() || values.get(0).isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(values.get(0));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String firstQuery(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String strOrNullQuery(PluginHttpRequest request, String key) {
        String value = firstQuery(request, key);
        return value == null || value.isBlank() ? null : value;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private PluginHttpResponse guard(HttpCall call) {
        try {
            return call.invoke();
        } catch (IllegalArgumentException e) {
            return PluginHttpResponse.rawJson(400, Map.of("message",
                    e.getMessage() == null ? "请求不合法" : e.getMessage()));
        } catch (IllegalStateException e) {
            return PluginHttpResponse.rawJson(500, Map.of("message",
                    e.getMessage() == null ? "处理失败" : e.getMessage()));
        } catch (RuntimeException e) {
            return PluginHttpResponse.rawJson(500, Map.of("message",
                    e.getMessage() == null ? "处理失败" : e.getMessage()));
        }
    }

    @FunctionalInterface
    private interface HttpCall {
        PluginHttpResponse invoke();
    }

    // ---------- 请求体 ----------

    private static OfficeSchema withId(OfficeSchema schema) {
        return schema.id() == null || schema.id().isBlank()
                ? new OfficeSchema(UUID.randomUUID().toString(), schema.code(), schema.name(), schema.role(),
                        schema.builtin(), schema.fields(), System.currentTimeMillis())
                : schema;
    }

    private static OfficeDocType withId(OfficeDocType docType) {
        return docType.id() == null || docType.id().isBlank()
                ? new OfficeDocType(UUID.randomUUID().toString(), docType.code(), docType.name(), docType.stage(),
                        docType.wordTemplateCode(), docType.schemaCodes(), docType.mappings(),
                        docType.fileNamePattern(), docType.enabled(), System.currentTimeMillis())
                : docType;
    }

    private static OfficePrompt withId(OfficePrompt prompt) {
        return prompt.id() == null || prompt.id().isBlank()
                ? new OfficePrompt(UUID.randomUUID().toString(), prompt.code(), prompt.name(), prompt.system(),
                        prompt.user(), System.currentTimeMillis())
                : prompt;
    }

    private static OfficeCategory withId(OfficeCategory category) {
        return category.id() == null || category.id().isBlank()
                ? new OfficeCategory(UUID.randomUUID().toString(), category.direction(), category.code(),
                        category.name(), category.fields(), category.schemaCode(), category.sort(),
                        category.enabled())
                : category;
    }

    private static OfficePackage withId(OfficePackage aPackage) {
        return aPackage.id() == null || aPackage.id().isBlank()
                ? new OfficePackage(UUID.randomUUID().toString(), aPackage.code(), aPackage.name(),
                        aPackage.dirPattern(), aPackage.items(), aPackage.enabled(), System.currentTimeMillis())
                : aPackage;
    }

    public record ReviewRequest(boolean accept, String note) {
    }

    public record PreviewRequest(String docTypeCode, String sourceType, String sourceId, String period) {
    }

    public record CreateJobRequest(String docTypeCode, String sourceType, String sourceId, String period) {
    }
}
