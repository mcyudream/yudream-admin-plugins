package online.yudream.base.plugin.aioffice.application;

import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeCategory;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeFinanceRecord;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeSettlementBatch;
import online.yudream.base.plugin.aioffice.domain.repo.OfficeRepository;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;

import java.io.ByteArrayInputStream;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 财务台账与周期结算：凭证登记、审核、关账锁定与结余结转。 */
public class FinanceService {

    /** 单凭证附件上限。 */
    public static final long MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024;

    private final OfficeRepository repository;
    private final PluginFileStore files;
    private final online.yudream.base.plugin.spi.system.FrameworkServices framework;

    public FinanceService(OfficeRepository repository, PluginFileStore files,
                          online.yudream.base.plugin.spi.system.FrameworkServices framework) {
        this.repository = repository;
        this.files = files;
        this.framework = framework;
    }

    // ---------- 登记 ----------

    /** 上传凭证附件（multipart 先落文件存储，返回引用供创建记录时提交）。kind 可由前端指定（invoice/screenshot/proof）。 */
    public OfficeFinanceRecord.FileRef uploadAttachment(String filename, String contentType, String kind, byte[] data) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("上传文件内容为空");
        }
        OfficeFinanceRecord.FileRef ref = storeAttachment(filename, contentType, data);
        return normalizeKind(ref, kind);
    }

    /** 平台文件桥：前端经 sdk.files.uploadImage 先传宿主 /api/files，这里按 fileId 拷入插件存储。 */
    public OfficeFinanceRecord.FileRef uploadPlatformFile(String fileId, String filenameHint, String kind) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("缺少平台文件 fileId");
        }
        online.yudream.base.plugin.spi.system.storage.PluginStoredFile platform;
        try {
            platform = framework.platformFile(fileId)
                    .orElseThrow(() -> new IllegalArgumentException("平台文件不存在或已被清理，请重新上传"));
        } catch (LinkageError e) {
            throw new IllegalStateException("宿主文件服务不可用，请升级宿主");
        }
        byte[] data;
        try (var input = platform.inputStream()) {
            data = input.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("平台文件读取失败：" + e.getMessage(), e);
        }
        if (data.length == 0) {
            throw new IllegalArgumentException("上传文件内容为空");
        }
        String filename = filenameHint != null && !filenameHint.isBlank()
                ? filenameHint : String.valueOf(platform.objectKey());
        return normalizeKind(storeAttachment(filename, platform.contentType(), data), kind);
    }

    /** 凭证类型白名单归一：invoice/screenshot/proof 之外的值回落 proof。 */
    private static OfficeFinanceRecord.FileRef normalizeKind(OfficeFinanceRecord.FileRef ref, String kind) {
        if (kind == null || kind.isBlank()) {
            return ref;
        }
        String normalized = kind.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"invoice".equals(normalized) && !"screenshot".equals(normalized)) {
            normalized = "proof";
        }
        return new OfficeFinanceRecord.FileRef(ref.fileKey(), ref.filename(), ref.contentType(), ref.size(),
                normalized);
    }

    private OfficeFinanceRecord.FileRef storeAttachment(String filename, String contentType, byte[] data) {
        if (data.length > MAX_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException("单个凭证不能超过 50MB");
        }
        String safeName = filename.replace('\\', '/');
        int slash = safeName.lastIndexOf('/');
        safeName = slash >= 0 ? safeName.substring(slash + 1) : safeName;
        if (safeName.isBlank()) {
            safeName = "file";
        }
        String objectKey = "finance/" + UUID.randomUUID() + "/" + safeName;
        String resolvedType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
            files.put(objectKey, input, data.length, resolvedType);
        } catch (Exception e) {
            throw new IllegalStateException("凭证保存失败：" + e.getMessage(), e);
        }
        return new OfficeFinanceRecord.FileRef(objectKey, safeName, resolvedType, data.length, "proof");
    }

    public OfficeFinanceRecord create(OfficeFinanceRecord record, boolean acceptOnCreate) {
        OfficeCategory category = repository.categories().stream()
                .filter(item -> item.code().equals(record.categoryCode())
                        && item.direction().equals(record.direction()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "经费类别不存在或方向不匹配：" + record.categoryCode()));
        OfficeFinanceRecord saved = repository.saveFinanceRecord(record);
        return acceptOnCreate ? repository.saveFinanceRecord(saved.review("system", true, "创建时直接采纳")) : saved;
    }

    public OfficeFinanceRecord review(String id, String reviewer, boolean accept, String note) {
        OfficeFinanceRecord record = repository.financeRecord(id)
                .orElseThrow(() -> new IllegalArgumentException("财务记录不存在"));
        if (record.settled()) {
            throw new IllegalArgumentException("已纳入结算的记录不能审核变更");
        }
        return repository.saveFinanceRecord(record.review(reviewer, accept, note));
    }

    /** 删除记录：已纳入结算的不可删；未结算的连同凭证文件清理。 */
    public void delete(String id) {
        OfficeFinanceRecord record = repository.financeRecord(id)
                .orElseThrow(() -> new IllegalArgumentException("财务记录不存在"));
        if (record.settled()) {
            throw new IllegalArgumentException("已纳入结算的记录不能删除");
        }
        for (OfficeFinanceRecord.FileRef attachment : record.attachments()) {
            try {
                files.delete(attachment.fileKey());
            } catch (RuntimeException ignored) {
                // 附件清理失败不阻塞删除
            }
        }
        repository.deleteFinanceRecord(id);
    }

    public List<OfficeFinanceRecord> records(String direction, String status, String categoryCode,
                                             String createdBy, int page, int size) {
        return repository.financeRecords(direction, status, categoryCode, createdBy, page, size);
    }

    public long countRecords(String direction, String status, String categoryCode, String createdBy) {
        return repository.countFinanceRecords(direction, status, categoryCode, createdBy);
    }

    public OfficeFinanceRecord requireOwned(String id, String userId) {
        OfficeFinanceRecord record = repository.financeRecord(id)
                .orElseThrow(() -> new IllegalArgumentException("财务记录不存在"));
        if (!record.createdBy().equals(userId)) {
            throw new IllegalArgumentException("只能操作本人登记的记录");
        }
        return record;
    }

    // ---------- 结算 ----------

    public List<OfficeSettlementBatch> batches() {
        return repository.settlementBatches();
    }

    /** 周期总览：批次状态 + 当期已采纳合计 + 待审数量。 */
    public Map<String, Object> overview(String period) {
        List<OfficeFinanceRecord> accepted = acceptedRecordsInPeriod(period);
        long income = accepted.stream()
                .filter(record -> record.direction().equals(OfficeFinanceRecord.DIRECTION_INCOME))
                .mapToLong(OfficeFinanceRecord::amountCents).sum();
        long expense = accepted.stream()
                .filter(record -> record.direction().equals(OfficeFinanceRecord.DIRECTION_EXPENSE))
                .mapToLong(OfficeFinanceRecord::amountCents).sum();
        long pending = repository.countFinanceRecords(null, OfficeFinanceRecord.STATUS_PENDING, null, null);
        OfficeSettlementBatch batch = repository.settlementBatch(period)
                .orElseGet(() -> OfficeSettlementBatch.open(period, carryInFromPrevious(period)));
        Map<String, Object> overview = new java.util.LinkedHashMap<>();
        overview.put("period", period);
        overview.put("status", batch.status());
        overview.put("incomeTotal", batch.closed() ? batch.incomeTotal() : income);
        overview.put("expenseTotal", batch.closed() ? batch.expenseTotal() : expense);
        overview.put("balanceCarryIn", batch.balanceCarryIn());
        overview.put("balanceCarryOut", batch.closed()
                ? batch.balanceCarryOut()
                : batch.balanceCarryIn() + income - expense);
        overview.put("pendingCount", pending);
        overview.put("closedAt", batch.closedAt());
        overview.put("closedBy", batch.closedBy());
        return overview;
    }

    /** 关账：当期已采纳记录纳入批次并锁定，结余结转下期。 */
    public OfficeSettlementBatch close(String period, String operator) {
        OfficeSettlementBatch existing = repository.settlementBatch(period).orElse(null);
        if (existing != null && existing.closed()) {
            throw new IllegalArgumentException("该周期已关账");
        }
        List<OfficeFinanceRecord> accepted = acceptedRecordsInPeriod(period);
        long income = accepted.stream()
                .filter(record -> record.direction().equals(OfficeFinanceRecord.DIRECTION_INCOME))
                .mapToLong(OfficeFinanceRecord::amountCents).sum();
        long expense = accepted.stream()
                .filter(record -> record.direction().equals(OfficeFinanceRecord.DIRECTION_EXPENSE))
                .mapToLong(OfficeFinanceRecord::amountCents).sum();
        long carryIn = existing != null ? existing.balanceCarryIn() : carryInFromPrevious(period);
        OfficeSettlementBatch batch = OfficeSettlementBatch.open(period, carryIn).close(income, expense, operator);
        OfficeSettlementBatch saved = repository.saveSettlementBatch(batch);
        for (OfficeFinanceRecord record : accepted) {
            if (!record.settled()) {
                repository.saveFinanceRecord(record.withSettlement(saved.id()));
            }
        }
        return saved;
    }

    /** 周期内已采纳记录（多段合并去重，按发生时间排序）。 */
    public List<OfficeFinanceRecord> acceptedRecordsInPeriod(String period) {
        long[][] segments = periodSegments(period);
        Map<String, OfficeFinanceRecord> unique = new java.util.LinkedHashMap<>();
        for (long[] segment : segments) {
            for (OfficeFinanceRecord record : repository.financeRecordsByPeriod(segment[0], segment[1])) {
                unique.putIfAbsent(record.id(), record);
            }
        }
        return unique.values().stream()
                .filter(OfficeFinanceRecord::accepted)
                .sorted(java.util.Comparator.comparingLong(OfficeFinanceRecord::occurredAt))
                .toList();
    }

    /** 上一个已关账批次（结束时间早于本周期最早开始）的结转结余；没有则为 0。 */
    private long carryInFromPrevious(String period) {
        long start = periodStart(period);
        return repository.settlementBatches().stream()
                .filter(OfficeSettlementBatch::closed)
                .filter(batch -> periodEndOf(batch.period()) < start)
                .max(java.util.Comparator.comparingLong(batch -> periodEndOf(batch.period())))
                .map(OfficeSettlementBatch::balanceCarryOut)
                .orElse(0L);
    }

    private static long periodEndOf(String period) {
        try {
            return periodBounds(period)[1];
        } catch (IllegalArgumentException e) {
            return 0L;
        }
    }

    /**
     * 周期 → 时间段列表（epoch millis，按起始排序并合并重叠/相接段）。
     * 支持多段：段间用逗号分隔，段内两种写法——月度 "yyyy-MM"、任意范围 "yyyy-MM-dd/yyyy-MM-dd"。
     * 例：2026-10 ｜ 2026-09-01/2026-12-01 ｜ 2026-09-01/2026-12-01,2027-03-01/2027-06-30
     */
    public static long[][] periodSegments(String period) {
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("周期不能为空");
        }
        List<long[]> segments = new ArrayList<>();
        for (String raw : period.trim().split(",")) {
            if (raw.isBlank()) {
                continue;
            }
            long[] bounds = singleSegmentBounds(raw.trim());
            segments.add(bounds);
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("周期不能为空：" + period);
        }
        segments.sort(java.util.Comparator.comparingLong(segment -> segment[0]));
        // 合并重叠/相接段，避免跨段重复统计
        List<long[]> merged = new ArrayList<>();
        for (long[] segment : segments) {
            if (!merged.isEmpty() && segment[0] <= merged.get(merged.size() - 1)[1] + 1) {
                long[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], segment[1]);
            }
            else {
                merged.add(new long[]{segment[0], segment[1]});
            }
        }
        return merged.toArray(new long[0][]);
    }

    /** 兼容旧调用：返回合并后的外包络 [最早开始, 最晚结束]。 */
    public static long[] periodBounds(String period) {
        long[][] segments = periodSegments(period);
        return new long[]{segments[0][0], segments[segments.length - 1][1]};
    }

    private static long[] singleSegmentBounds(String segment) {
        String trimmed = segment;
        if (trimmed.matches("\\d{4}-\\d{2}")) {
            YearMonth month;
            try {
                month = YearMonth.parse(trimmed);
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("周期月份无效：" + segment, e);
            }
            long from = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            return new long[]{from, to - 1};
        }
        String[] parts = trimmed.split("/");
        if (parts.length != 2 || !parts[0].matches("\\d{4}-\\d{2}-\\d{2}") || !parts[1].matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException(
                    "周期段格式应为 yyyy-MM 或 yyyy-MM-dd/yyyy-MM-dd，多段用逗号分隔：" + segment);
        }
        try {
            long from = java.time.LocalDate.parse(parts[0]).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long to = java.time.LocalDate.parse(parts[1]).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (to <= from) {
                throw new IllegalArgumentException("周期段结束必须晚于开始：" + segment);
            }
            return new long[]{from, to - 1};
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("周期日期无效：" + segment, e);
        }
    }

    /** 周期起始时刻（结余链按时间衔接，兼容月度与范围两种写法）。 */
    public static long periodStart(String period) {
        return periodBounds(period)[0];
    }

    /** 周期展示名转安全文件名片段：'/' 会破坏 zip 目录结构，替换为 '～'。 */
    public static String safePeriodLabel(String period) {
        return period == null ? "" : period.trim().replace('/', '～');
    }

    /** 当期月份（站点时区）。 */
    public static String currentPeriod() {
        return YearMonth.now(ZoneId.systemDefault()).toString();
    }

    /** 当期已采纳支出/收入的行数据（供文档渲染），已按发生时间排序。 */
    public List<Map<String, Object>> periodRows(String period, String direction) {
        List<Map<String, Object>> rows = new ArrayList<>();
        java.text.SimpleDateFormat date = new java.text.SimpleDateFormat("yyyy年MM月dd日");
        for (OfficeFinanceRecord record : acceptedRecordsInPeriod(period)) {
            if (!record.direction().equals(direction)) {
                continue;
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("title", record.title());
            row.put("category", record.categoryCode());
            row.put("subCategory", record.subCategory());
            row.put("amountCents", record.amountCents());
            row.put("amount", online.yudream.base.plugin.aioffice.domain.service.MappingResolver
                    .moneyText(record.amountCents()));
            row.put("quantity", record.quantity());
            row.put("unitPriceCents", record.unitPriceCents());
            row.put("unitPrice", online.yudream.base.plugin.aioffice.domain.service.MappingResolver
                    .moneyText(record.unitPriceCents()));
            row.put("buyer", record.buyerName());
            row.put("occurredAt", record.occurredAt());
            row.put("dateText", date.format(new java.util.Date(record.occurredAt())));
            row.put("purpose", record.purpose());
            row.put("fundSource", record.fundSource());
            row.put("data", new java.util.LinkedHashMap<>(record.data()));
            rows.add(row);
        }
        return rows;
    }
}
