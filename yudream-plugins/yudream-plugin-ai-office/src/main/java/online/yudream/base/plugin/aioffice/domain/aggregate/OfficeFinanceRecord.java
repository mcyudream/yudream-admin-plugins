package online.yudream.base.plugin.aioffice.domain.aggregate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 财务台账记录：收入/支出 + 凭证附件（发票、支付截图），经审核后纳入周期结算。金额一律为分。 */
public record OfficeFinanceRecord(
        String id,
        String direction,
        String categoryCode,
        String subCategory,
        String title,
        long amountCents,
        long quantity,
        long unitPriceCents,
        String buyerId,
        String buyerName,
        long occurredAt,
        String purpose,
        String fundSource,
        List<FileRef> attachments,
        String status,
        String reviewedBy,
        long reviewedAt,
        String reviewNote,
        String settlementBatchId,
        String createdBy,
        long createdAt
) {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_REJECTED = "rejected";
    public static final String DIRECTION_INCOME = "income";
    public static final String DIRECTION_EXPENSE = "expense";

    public OfficeFinanceRecord {
        direction = require(direction, "收支方向不能为空").toLowerCase(Locale.ROOT);
        if (!DIRECTION_INCOME.equals(direction) && !DIRECTION_EXPENSE.equals(direction)) {
            throw new IllegalArgumentException("收支方向只能是 income 或 expense");
        }
        categoryCode = require(categoryCode, "经费类别不能为空");
        title = require(title, "摘要不能为空");
        subCategory = text(subCategory);
        amountCents = Math.max(amountCents, 0);
        quantity = Math.max(quantity, 0);
        unitPriceCents = Math.max(unitPriceCents, 0);
        buyerId = text(buyerId);
        buyerName = text(buyerName);
        occurredAt = Math.max(occurredAt, 0);
        purpose = text(purpose);
        fundSource = text(fundSource);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        status = status == null || status.isBlank() ? STATUS_PENDING : status.trim().toLowerCase(Locale.ROOT);
        reviewedBy = text(reviewedBy);
        reviewNote = text(reviewNote);
        settlementBatchId = text(settlementBatchId);
        createdBy = text(createdBy);
    }

    /** 凭证附件引用：kind ∈ invoice（发票）| screenshot（支付截图）| proof（其他凭证）。 */
    public record FileRef(String fileKey, String filename, String contentType, long size, String kind) {
        public FileRef {
            fileKey = require(fileKey, "附件 fileKey 不能为空");
            filename = text(filename);
            contentType = text(contentType);
            size = Math.max(size, 0);
            kind = kind == null || kind.isBlank() ? "proof" : kind.trim().toLowerCase(Locale.ROOT);
        }
    }

    public static OfficeFinanceRecord create(String direction, String categoryCode, String subCategory,
                                             String title, long amountCents, long quantity, long unitPriceCents,
                                             String buyerId, String buyerName, long occurredAt, String purpose,
                                             String fundSource, List<FileRef> attachments, String createdBy) {
        return new OfficeFinanceRecord(UUID.randomUUID().toString(), direction, categoryCode, subCategory, title,
                amountCents, quantity, unitPriceCents, buyerId, buyerName, occurredAt, purpose, fundSource,
                attachments, STATUS_PENDING, "", 0, "", "", createdBy, System.currentTimeMillis());
    }

    public boolean accepted() {
        return STATUS_ACCEPTED.equals(status);
    }

    public boolean pending() {
        return STATUS_PENDING.equals(status);
    }

    public boolean settled() {
        return settlementBatchId != null && !settlementBatchId.isBlank();
    }

    public OfficeFinanceRecord review(String reviewer, boolean accept, String note) {
        if (!pending()) {
            throw new IllegalArgumentException("该记录已审核过");
        }
        return new OfficeFinanceRecord(id, direction, categoryCode, subCategory, title, amountCents, quantity,
                unitPriceCents, buyerId, buyerName, occurredAt, purpose, fundSource, attachments,
                accept ? STATUS_ACCEPTED : STATUS_REJECTED, reviewer, System.currentTimeMillis(), note,
                settlementBatchId, createdBy, createdAt);
    }

    public OfficeFinanceRecord withSettlement(String batchId) {
        return new OfficeFinanceRecord(id, direction, categoryCode, subCategory, title, amountCents, quantity,
                unitPriceCents, buyerId, buyerName, occurredAt, purpose, fundSource, attachments, status,
                reviewedBy, reviewedAt, reviewNote, batchId, createdBy, createdAt);
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
