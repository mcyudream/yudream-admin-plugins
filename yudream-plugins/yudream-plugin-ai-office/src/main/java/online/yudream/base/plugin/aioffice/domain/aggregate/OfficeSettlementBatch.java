package online.yudream.base.plugin.aioffice.domain.aggregate;

import java.util.Locale;

/** 周期结算批次：关账后锁定当期已采纳记录，结余结转下期。period 形如 2026-10。 */
public record OfficeSettlementBatch(
        String id,
        String period,
        String status,
        long incomeTotal,
        long expenseTotal,
        long balanceCarryIn,
        long balanceCarryOut,
        long closedAt,
        String closedBy
) {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_CLOSED = "closed";

    public OfficeSettlementBatch {
        period = require(period, "结算周期不能为空");
        if (!isValidPeriod(period)) {
            throw new IllegalArgumentException("结算周期格式应为 yyyy-MM 或 yyyy-MM-dd/yyyy-MM-dd：" + period);
        }
        status = status == null || status.isBlank() ? STATUS_OPEN : status.trim().toLowerCase(Locale.ROOT);
        closedBy = closedBy == null ? "" : closedBy.trim();
    }

    /** 周期写法：月度 "yyyy-MM"，或任意时间范围 "yyyy-MM-dd/yyyy-MM-dd"（如 2026-09-01/2026-12-01）。 */
    public static boolean isValidPeriod(String period) {
        if (period == null || period.isBlank()) {
            return false;
        }
        String trimmed = period.trim();
        if (trimmed.matches("\\d{4}-\\d{2}")) {
            return true;
        }
        String[] parts = trimmed.split("/");
        return parts.length == 2
                && parts[0].matches("\\d{4}-\\d{2}-\\d{2}")
                && parts[1].matches("\\d{4}-\\d{2}-\\d{2}");
    }

    public static OfficeSettlementBatch open(String period, long balanceCarryIn) {
        return new OfficeSettlementBatch(period, period, STATUS_OPEN, 0, 0, balanceCarryIn, balanceCarryIn,
                0, "");
    }

    public boolean open() {
        return STATUS_OPEN.equals(status);
    }

    public boolean closed() {
        return STATUS_CLOSED.equals(status);
    }

    /** 关账：锁定合计并计算结余。 */
    public OfficeSettlementBatch close(long incomeTotal, long expenseTotal, String closedBy) {
        if (!open()) {
            throw new IllegalArgumentException("该周期已关账");
        }
        return new OfficeSettlementBatch(id, period, STATUS_CLOSED, Math.max(incomeTotal, 0),
                Math.max(expenseTotal, 0), balanceCarryIn,
                balanceCarryIn + Math.max(incomeTotal, 0) - Math.max(expenseTotal, 0),
                System.currentTimeMillis(), closedBy == null ? "" : closedBy.trim());
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
