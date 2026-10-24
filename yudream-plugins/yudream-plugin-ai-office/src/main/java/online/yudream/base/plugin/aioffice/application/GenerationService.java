package online.yudream.base.plugin.aioffice.application;

import online.yudream.base.plugin.activityproof.api.ActivityBrief;
import online.yudream.base.plugin.activityproof.api.ActivityEffectSummary;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocJob;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocType;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeFinanceRecord;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficePackage;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficePrompt;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeSettings;
import online.yudream.base.plugin.aioffice.domain.repo.OfficeRepository;
import online.yudream.base.plugin.aioffice.domain.service.ImageMappingSupport;
import online.yudream.base.plugin.aioffice.domain.service.MappingResolver;
import online.yudream.base.plugin.aioffice.infrastructure.support.ActivityPlatformBridge;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.document.PluginRenderedDocument;
import online.yudream.base.plugin.spi.system.document.PluginWordTemplateImage;
import online.yudream.base.plugin.spi.system.document.PluginWordTemplateService;
import online.yudream.base.plugin.spi.system.document.PluginWordTemplateSummary;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse;
import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;
import online.yudream.base.plugin.spi.system.preview.PluginFilePreviewService;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 文档生成：上下文构建、映射预览、AI 起草（人工确认后定稿）、渲染与打包导出。 */
public class GenerationService {

    /** AI 单次起草超时（秒）。 */
    private static final int AI_TIMEOUT_SECONDS = 240;

    private final OfficeRepository repository;
    private final FinanceService finance;
    private final PluginContext context;
    private final PluginFileStore files;
    private final ActivityPlatformBridge activities;

    public GenerationService(OfficeRepository repository, FinanceService finance, PluginContext context) {
        this.repository = repository;
        this.finance = finance;
        this.context = context;
        this.files = context.files();
        this.activities = new ActivityPlatformBridge(context);
    }

    // ---------- 上下文 ----------

    /**
     * 组装映射解析上下文。分支：ACTIVITY（活动平台活动）| RECORD（数据集记录）| MONTH（周期聚合）。
     * 通用键：org（档案）、target（主对象含 attachments）、effects、activities、stats、finance。
     */
    public Map<String, Object> context(String sourceType, String sourceId, String period) {
        OfficeSettings settings = repository.settings();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("org", new LinkedHashMap<>(settings.profile()));
        String normalizedPeriod = period == null || period.isBlank() ? FinanceService.currentPeriod() : period;
        ctx.put("period", normalizedPeriod);
        ctx.put("today", System.currentTimeMillis());
        ctx.put("todayText", dateText(System.currentTimeMillis()));

        List<Map<String, Object>> effects = new ArrayList<>();
        long participants = 0;
        switch (type(sourceType)) {
            case OfficeDocJob.SOURCE_ACTIVITY -> {
                ActivityBrief brief = activities.activity(sourceId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "活动不存在（需安装并启用活动平台 minecraft-activity-proof）"));
                Map<String, Object> target = activityMap(brief);
                participants = brief.participantCount();
                List<Map<String, Object>> attachments = new ArrayList<>();
                List<String> texts = new ArrayList<>();
                for (ActivityEffectSummary effect : activities.acceptedEffects(sourceId)) {
                    Map<String, Object> effectMap = effectMap(effect);
                    effects.add(effectMap);
                    if (effect.text() != null && !effect.text().isBlank()) {
                        texts.add(effect.text());
                    }
                    for (Map<String, Object> attachment : attachmentList(effect)) {
                        Map<String, Object> item = new LinkedHashMap<>(attachment);
                        item.put("effectText", effect.text());
                        attachments.add(item);
                    }
                }
                target.put("attachments", attachments);
                target.put("effectsText", String.join("\n", texts));
                ctx.put("activity", target);
                ctx.put("target", target);
            }
            case OfficeDocJob.SOURCE_RECORD -> {
                Map<String, Object> target = repository.datasetRecord(sourceId)
                        .map(record -> {
                            Map<String, Object> map = new LinkedHashMap<>();
                            map.put("id", record.id());
                            map.put("schemaCode", record.schemaCode());
                            map.putAll(record.data());
                            map.put("attachments", attachmentListWithUrls(
                                    financeRecordRefs(record.attachments())));
                            return map;
                        })
                        .orElseThrow(() -> new IllegalArgumentException("数据记录不存在"));
                ctx.put("record", target);
                ctx.put("target", target);
            }
            default -> {
                long[] bounds = FinanceService.periodBounds(normalizedPeriod);
                List<Map<String, Object>> list = new ArrayList<>();
                StringBuilder activitiesText = new StringBuilder();
                for (ActivityBrief brief : activities.activitiesBetween(bounds[0], bounds[1])) {
                    list.add(activityMap(brief));
                    if (activitiesText.length() > 0) {
                        activitiesText.append("\n");
                    }
                    activitiesText.append("【").append(brief.title()).append("】");
                    if (brief.summary() != null && !brief.summary().isBlank()) {
                        activitiesText.append(brief.summary());
                    }
                }
                ctx.put("activities", list);
                ctx.put("activitiesText", activitiesText.toString());
                // 数据对象记录：非活动平台站点的主路径（任意 schema 的实例按创建时间入周期）
                List<Map<String, Object>> recordMaps = new ArrayList<>();
                StringBuilder recordsText = new StringBuilder();
                for (online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDatasetRecord record
                        : repository.datasetRecordsByPeriod(bounds[0], bounds[1])) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", record.id());
                    map.put("schemaCode", record.schemaCode());
                    map.put("createdAt", record.createdAt());
                    map.put("createdAtText", dateText(record.createdAt()));
                    map.putAll(record.data());
                    map.put("attachments", attachmentListWithUrls(record.attachments()));
                    recordMaps.add(map);
                    Object title = record.data().getOrDefault("title", record.data().get("name"));
                    if (recordsText.length() > 0) {
                        recordsText.append("\n");
                    }
                    recordsText.append("【").append(record.schemaCode()).append("】")
                            .append(title == null ? "" : title);
                }
                ctx.put("records", recordMaps);
                ctx.put("recordsText", recordsText.toString());
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("period", normalizedPeriod);
                target.put("activityCount", list.size());
                ctx.put("target", target);
                for (ActivityEffectSummary effect : activities.acceptedEffectsBetween(bounds[0], bounds[1])) {
                    effects.add(effectMap(effect));
                }
            }
        }
        ctx.put("effects", effects);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("participants", participants);
        stats.put("activityCount", ctx.containsKey("activities") ? ((List<?>) ctx.get("activities")).size() : 0);
        stats.put("recordCount", ctx.containsKey("records") ? ((List<?>) ctx.get("records")).size() : 0);
        stats.put("effectCount", effects.size());
        // 实体总数 = 活动平台活动 + 数据对象记录（非社团站点只有后者）
        stats.put("entityCount", ((Number) stats.get("activityCount")).intValue()
                + ((Number) stats.get("recordCount")).intValue());
        ctx.put("stats", stats);
        ctx.put("finance", financeContext(normalizedPeriod));
        return ctx;
    }

    /** 财务上下文：周期合计 + 已采纳收入/支出行。 */
    public Map<String, Object> financeContext(String period) {
        Map<String, Object> overview = finance.overview(period);
        Map<String, Object> financeMap = new LinkedHashMap<>(overview);
        financeMap.put("incomes", finance.periodRows(period, OfficeFinanceRecord.DIRECTION_INCOME));
        financeMap.put("expenses", finance.periodRows(period, OfficeFinanceRecord.DIRECTION_EXPENSE));
        return financeMap;
    }

    /** 映射解析预览：每个映射变量的解析结果与缺失标记。 */
    public Map<String, Object> preview(String docTypeCode, String sourceType, String sourceId, String period) {
        OfficeDocType docType = requireDocType(docTypeCode);
        Map<String, Object> ctx = context(sourceType, sourceId, period);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OfficeDocType.Mapping mapping : docType.mappings()) {
            Object resolved = MappingResolver.resolve(mapping.source(), ctx, Map.of());
            Object transformed = resolved instanceof List<?> list
                    ? "列表[" + list.size() + " 项]"
                    : MappingResolver.applyTransform(mapping.transform(), resolved);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("var", mapping.var());
            row.put("source", mapping.source());
            row.put("value", transformed);
            row.put("missing", resolved == null || "".equals(resolved));
            row.put("ai", mapping.source().startsWith("ai:"));
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("context", ctx);
        return result;
    }

    // ---------- 任务生命周期 ----------

    public OfficeDocJob createJob(String docTypeCode, String sourceType, String sourceId, String period,
                                  String operatorId) {
        requireDocType(docTypeCode);
        return repository.saveDocJob(OfficeDocJob.create(docTypeCode, sourceType, sourceId, period, operatorId));
    }

    public OfficeDocJob job(String id) {
        return repository.docJob(id).orElseThrow(() -> new IllegalArgumentException("生成任务不存在"));
    }

    public List<OfficeDocJob> jobs(String docTypeCode, String status, int page, int size) {
        return repository.docJobs(docTypeCode, status, page, size);
    }

    public long countJobs(String docTypeCode, String status) {
        return repository.countDocJobs(docTypeCode, status);
    }

    public void deleteJob(String id) {
        OfficeDocJob job = job(id);
        if (job.resultFileKey() != null && !job.resultFileKey().isBlank()) {
            try {
                files.delete(job.resultFileKey());
            } catch (RuntimeException ignored) {
                // 产物清理失败不阻塞删除
            }
        }
        repository.deleteDocJob(id);
    }

    public OfficeDocJob saveDraft(String id, Map<String, String> draft) {
        OfficeDocJob job = job(id);
        if (OfficeDocJob.STATUS_FINAL.equals(job.status())) {
            throw new IllegalArgumentException("已定稿任务不能再编辑草稿");
        }
        return repository.saveDocJob(job.withDraft(draft == null ? Map.of() : draft, job.status()));
    }

    /** AI 起草：为 source=ai: 的映射变量逐一生成草稿（同步阻塞，产物仅入草稿待人工确认）。 */
    public OfficeDocJob runAi(String id, String operatorId) {
        OfficeDocJob job = job(id);
        OfficeDocType docType = requireDocType(job.docTypeCode());
        OfficeSettings settings = repository.settings();
        PluginAiService ai = aiOrNull();
        if (ai == null) {
            throw new IllegalArgumentException("AI 能力未启用：请在平台能力配置中启用 AI 并配置提供商");
        }
        Map<String, Object> ctx = context(job.sourceType(), job.sourceId(), job.period());
        OfficeDocJob updated = job;
        for (OfficeDocType.Mapping mapping : docType.mappings()) {
            if (!mapping.source().startsWith("ai:")) {
                continue;
            }
            String promptCode = mapping.source().substring(3).trim();
            OfficePrompt prompt = repository.promptByCode(promptCode)
                    .orElseThrow(() -> new IllegalArgumentException("提示词不存在：" + promptCode));
            String system = prompt.system();
            if (settings.styleGuide() != null && !settings.styleGuide().isBlank()) {
                system = system.isBlank() ? settings.styleGuide()
                        : system + "\n\n【站点写作规范】\n" + settings.styleGuide();
            }
            String user = MappingResolver.interpolate(prompt.user(), ctx, updated.draft());
            String content = chat(ai, settings, system, user, operatorId);
            updated = updated.withAiDraft(mapping.var(), content, null);
        }
        return repository.saveDocJob(updated);
    }

    /** 定稿渲染：映射解析（ai: 变量取草稿值）→ 宿主 Word 模板渲染 → 产物入文件存储。 */
    public OfficeDocJob render(String id) {
        OfficeDocJob job = job(id);
        OfficeDocType docType = requireDocType(job.docTypeCode());
        if (docType.wordTemplateCode() == null || docType.wordTemplateCode().isBlank()) {
            throw new IllegalArgumentException("该文档类型未绑定宿主 Word 模板");
        }
        Map<String, Object> ctx = context(job.sourceType(), job.sourceId(), job.period());
        Map<String, Object> data = MappingResolver.buildRenderData(docType.mappings(), ctx, job.draft());
        applyImageMappings(docType, data);
        PluginWordTemplateService wordTemplates = wordTemplatesOrNull();
        if (wordTemplates == null) {
            throw new IllegalArgumentException("宿主 Word 模板能力未启用（平台能力 document-template）");
        }
        PluginWordTemplateSummary template = wordTemplates.templateByCode(docType.wordTemplateCode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "宿主 Word 模板不存在：" + docType.wordTemplateCode() + "，请先在平台管理端上传"));
        PluginRenderedDocument rendered;
        try {
            rendered = wordTemplates.render(template.id(), data);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            throw new IllegalArgumentException("宿主不支持按模板渲染，请升级宿主后重试：" + e.getMessage(), e);
        }
        String filename = safeFilename(job, ctx, docType);
        String objectKey = "docs/" + job.id() + "/" + filename;
        try (ByteArrayInputStream input = new ByteArrayInputStream(rendered.content())) {
            files.put(objectKey, input, rendered.content().length,
                    rendered.contentType() == null ? "application/vnd.openxmlformats-officedocument"
                            + ".wordprocessingml.document" : rendered.contentType());
        } catch (Exception e) {
            throw new IllegalStateException("产物保存失败：" + e.getMessage(), e);
        }
        return repository.saveDocJob(job.withResult(objectKey, filename));
    }

    // ---------- 打包导出 ----------

    /** 打包预检：每个打包项在周期内的生成状态。 */
    public List<Map<String, Object>> precheck(String packageCode, String period) {
        OfficePackage aPackage = repository.packageByCode(packageCode)
                .orElseThrow(() -> new IllegalArgumentException("打包定义不存在：" + packageCode));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OfficePackage.Item item : aPackage.items()) {
            OfficeDocType docType = repository.docTypeByCode(item.docTypeCode()).orElse(null);
            List<String> targets = resolveTargets(item.target(), period);
            for (String target : targets) {
                String[] parts = target.split("\\|", 2);
                String targetId = parts[0];
                String targetLabel = parts.length > 1 ? parts[1] : targetId;
                Optional<OfficeDocJob> finalJob = repository
                        .docJobsByTarget(item.docTypeCode(), sourceTypeOf(item.target()), targetId, period)
                        .stream().filter(job -> OfficeDocJob.STATUS_FINAL.equals(job.status()))
                        .findFirst();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("docTypeCode", item.docTypeCode());
                row.put("docTypeName", docType == null ? item.docTypeCode() : docType.name());
                row.put("targetId", targetId);
                row.put("targetLabel", targetLabel);
                row.put("ready", finalJob.isPresent());
                row.put("jobId", finalJob.map(OfficeDocJob::id).orElse(""));
                row.put("filename", finalJob.map(OfficeDocJob::resultFilename).orElse(""));
                row.put("missing", docType == null);
                rows.add(row);
            }
        }
        return rows;
    }

    /** 打包导出 zip：优先复用已定稿产物，缺失项按 skipMissing 决定跳过或报错。 */
    public byte[] exportPackage(String packageCode, String period, boolean skipMissing, String operatorId) {
        OfficePackage aPackage = repository.packageByCode(packageCode)
                .orElseThrow(() -> new IllegalArgumentException("打包定义不存在：" + packageCode));
        List<String> missing = new ArrayList<>();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        List<String[]> entryNames = new ArrayList<>();
        String rootDir = safeDir(MappingResolver.interpolate(aPackage.dirPattern(),
                Map.of("org", repository.settings().profile()), Map.of())
                .replace("{month汉字}", chineseMonth(period))
                .replace("{month}", period));
        for (OfficePackage.Item item : aPackage.items()) {
            List<String> targets = resolveTargets(item.target(), period);
            for (String target : targets) {
                String[] parts = target.split("\\|", 2);
                String targetId = parts[0];
                String targetLabel = parts.length > 1 ? parts[1] : targetId;
                Optional<OfficeDocJob> finalJob = repository
                        .docJobsByTarget(item.docTypeCode(), sourceTypeOf(item.target()), targetId, period)
                        .stream().filter(job -> OfficeDocJob.STATUS_FINAL.equals(job.status()))
                        .findFirst();
                if (finalJob.isEmpty()) {
                    missing.add(item.docTypeCode() + " / " + targetLabel);
                    continue;
                }
                OfficeDocJob job = finalJob.get();
                byte[] content;
                try (var stream = files.get(job.resultFileKey()).inputStream()) {
                    content = stream.readAllBytes();
                } catch (Exception e) {
                    missing.add(item.docTypeCode() + " / " + targetLabel + "（产物读取失败）");
                    continue;
                }
                String dir = safeDir(MappingResolver.interpolate(item.subDir(),
                        Map.of("activity", Map.of("title", targetLabel)), Map.of()));
                String path = (rootDir.isBlank() ? "" : rootDir + "/")
                        + (dir.isBlank() ? "" : dir + "/")
                        + (job.resultFilename().isBlank() ? job.id() + ".docx" : job.resultFilename());
                entryNames.add(new String[]{path, job.id()});
                entries.put(job.id(), content);
            }
        }
        if (!missing.isEmpty() && !skipMissing) {
            throw new IllegalArgumentException("存在未定稿文档：" + String.join("；", missing)
                    + "。请先生成或选择跳过缺失项。");
        }
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (String[] entry : entryNames) {
                zip.putNextEntry(new ZipEntry(entry[0]));
                zip.write(entries.get(entry[1]));
                zip.closeEntry();
            }
            zip.finish();
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("打包失败：" + e.getMessage(), e);
        }
    }

    /** 打包目标清单：PER_ACTIVITY → 周期内活动（id|标题），PER_MONTH → 单个周期目标。 */
    private List<String> resolveTargets(String targetKind, String period) {
        long[] bounds = FinanceService.periodBounds(period);
        List<String> targets = new ArrayList<>();
        if ("PER_ACTIVITY".equals(targetKind)) {
            for (ActivityBrief brief : activities.activitiesBetween(bounds[0], bounds[1])) {
                targets.add(brief.id() + "|" + brief.title());
            }
        } else {
            targets.add(period + "|" + period);
        }
        return targets;
    }

    private static String sourceTypeOf(String targetKind) {
        return "PER_ACTIVITY".equals(targetKind)
                ? OfficeDocJob.SOURCE_ACTIVITY : OfficeDocJob.SOURCE_MONTH;
    }

    // ---------- 内部 ----------

    /**
     * 嵌图转换：映射声明为图片（顶层 transform=image[:宽px]、行数据源 image:前缀）时，
     * 把解析出的文件键/附件描述转换为 SPI 图片值；开关关闭、非图片或不可读时置空串，
     * 避免老引擎把未知类型渲染成对象 toString 垃圾串。
     */
    private void applyImageMappings(OfficeDocType docType, Map<String, Object> data) {
        boolean embed = repository.settings().embedImages();
        for (String var : ImageMappingSupport.topLevelImageVars(docType.mappings())) {
            Object value = data.get(var);
            PluginWordTemplateImage image = embed
                    ? ImageMappingSupport.toImage(value, this::loadImageBytes)
                    : null;
            data.put(var, image != null ? image : "");
        }
        Map<String, List<String>> rowVars = ImageMappingSupport.rowImageVars(docType.mappings());
        for (Map.Entry<String, List<String>> entry : rowVars.entrySet()) {
            if (!(data.get(entry.getKey()) instanceof List<?> rows)) {
                continue;
            }
            List<Object> converted = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> raw) {
                    Map<String, Object> mutable = new LinkedHashMap<>();
                    raw.forEach((key, value) -> mutable.put(String.valueOf(key), value));
                    for (String rowVar : entry.getValue()) {
                        PluginWordTemplateImage image = embed
                                ? ImageMappingSupport.toImage(mutable.get(rowVar), this::loadImageBytes)
                                : null;
                        mutable.put(rowVar, image != null ? image : "");
                    }
                    converted.add(mutable);
                }
                else {
                    converted.add(row);
                }
            }
            data.put(entry.getKey(), converted);
        }
    }

    private byte[] loadImageBytes(String fileKey) {
        try (InputStream input = files.get(fileKey).inputStream()) {
            return input.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private static String type(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return OfficeDocJob.SOURCE_MONTH;
        }
        return switch (sourceType.trim().toUpperCase(java.util.Locale.ROOT)) {
            case OfficeDocJob.SOURCE_ACTIVITY -> OfficeDocJob.SOURCE_ACTIVITY;
            case OfficeDocJob.SOURCE_RECORD -> OfficeDocJob.SOURCE_RECORD;
            default -> OfficeDocJob.SOURCE_MONTH;
        };
    }

    private OfficeDocType requireDocType(String code) {
        return repository.docTypeByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("文档类型不存在：" + code));
    }

    private Map<String, Object> activityMap(ActivityBrief brief) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", brief.id());
        map.put("title", brief.title());
        map.put("summary", brief.summary());
        map.put("description", brief.description());
        map.put("coverUrl", brief.coverUrl());
        map.put("status", brief.status());
        map.put("signupStart", brief.signupStart());
        map.put("signupEnd", brief.signupEnd());
        map.put("activityStart", brief.activityStart());
        map.put("activityEnd", brief.activityEnd());
        map.put("publishedAt", brief.publishedAt());
        map.put("participantCount", brief.participantCount());
        map.put("activityStartText", dateText(brief.activityStart()));
        map.put("activityEndText", dateText(brief.activityEnd()));
        return map;
    }

    private Map<String, Object> effectMap(ActivityEffectSummary effect) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", effect.id());
        map.put("text", effect.text());
        map.put("submittedAt", effect.submittedAt());
        map.put("submittedBy", effect.submittedBy());
        map.put("attachments", attachmentList(effect));
        List<String> captions = new ArrayList<>();
        for (Map<String, Object> attachment : attachmentList(effect)) {
            String caption = String.valueOf(attachment.getOrDefault("caption", ""));
            captions.add(caption.isBlank() ? String.valueOf(attachment.get("filename")) : caption);
        }
        map.put("captions", String.join("；", captions));
        return map;
    }

    private List<Map<String, Object>> attachmentList(ActivityEffectSummary effect) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ActivityEffectSummary.Attachment attachment : effect.attachments()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileKey", attachment.fileKey());
            item.put("filename", attachment.filename());
            item.put("contentType", attachment.contentType());
            item.put("kind", attachment.kind());
            item.put("caption", attachment.caption());
            item.put("url", signedUrl(attachment.fileKey(), attachment.filename()));
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> attachmentListWithUrls(List<OfficeFinanceRecord.FileRef> refs) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (OfficeFinanceRecord.FileRef ref : refs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileKey", ref.fileKey());
            item.put("filename", ref.filename());
            item.put("kind", ref.kind());
            item.put("url", signedUrl(ref.fileKey(), ref.filename()));
            list.add(item);
        }
        return list;
    }

    private List<OfficeFinanceRecord.FileRef> financeRecordRefs(List<OfficeFinanceRecord.FileRef> refs) {
        return refs;
    }

    private String signedUrl(String objectKey, String filename) {
        try {
            PluginFilePreviewService preview = context.framework().filePreview();
            return preview.signedFileUrl(context.pluginCode(), objectKey,
                    filename == null || filename.isBlank() ? "file" : filename);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String chat(PluginAiService ai, OfficeSettings settings, String system, String user,
                        String operatorId) {
        try {
            PluginAiExecutionContext executionContext = new PluginAiExecutionContext(
                    operatorId == null || operatorId.isBlank() ? null : Long.parseLong(operatorId),
                    null, null, null, null, "AI_OFFICE_DRAFT",
                    UUID.randomUUID().toString(), List.of());
            PluginAiChatResponse response = ai.chat(new PluginAiChatRequest(
                    system, user, blankToNull(settings.aiProviderCode()), blankToNull(settings.aiModelCode()),
                    List.of(), executionContext, false))
                    .toCompletableFuture()
                    .get(AI_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (response == null || response.content() == null || response.content().isBlank()) {
                throw new IllegalStateException("AI 未返回内容");
            }
            return response.content().trim();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AI 起草失败：" + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()),
                    e);
        }
    }

    private PluginAiService aiOrNull() {
        try {
            FrameworkServices framework = context.framework();
            PluginAiService ai = framework.ai();
            ai.providers();
            return ai;
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    private PluginWordTemplateService wordTemplatesOrNull() {
        try {
            return context.framework().wordTemplates();
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    private String safeFilename(OfficeDocJob job, Map<String, Object> ctx, OfficeDocType docType) {
        String pattern = docType.fileNamePattern() == null || docType.fileNamePattern().isBlank()
                ? docType.name() + "-{activity.title}"
                : docType.fileNamePattern();
        String name = MappingResolver.interpolate(pattern, ctx, job.draft())
                .replace("{month汉字}", chineseMonth(job.period()))
                .replace("{month}", job.period());
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isBlank()) {
            name = job.id();
        }
        return name.endsWith(".docx") ? name : name + ".docx";
    }

    private static String safeDir(String dir) {
        String cleaned = dir == null ? "" : dir.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.replaceAll("/+", "/");
    }

    private static String chineseMonth(String period) {
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            return period == null ? "" : period;
        }
        String[] digits = {"〇", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
                "十一", "十二"};
        int month = Integer.parseInt(period.substring(5, 7));
        return month >= 1 && month <= 12 ? digits[month] : period;
    }

    private static String dateText(long epoch) {
        return epoch <= 0 ? "" : new SimpleDateFormat("yyyy年MM月dd日").format(new Date(epoch));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
