package online.yudream.base.plugin.aioffice;

import online.yudream.base.plugin.aioffice.application.FinanceService;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeSettlementBatch;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlementTest {

    @Test
    void closeComputesBalance() {
        OfficeSettlementBatch batch = OfficeSettlementBatch.open("2026-10", 10000);
        OfficeSettlementBatch closed = batch.close(50000, 30000, "admin");
        assertEquals(OfficeSettlementBatch.STATUS_CLOSED, closed.status());
        assertEquals(10000, closed.balanceCarryIn());
        assertEquals(30000, closed.balanceCarryOut());
    }

    @Test
    void doubleCloseRejected() {
        OfficeSettlementBatch closed = OfficeSettlementBatch.open("2026-10", 0).close(1, 1, "admin");
        assertThrows(IllegalArgumentException.class, () -> closed.close(1, 1, "admin"));
    }

    @Test
    void periodMustMatchFormat() {
        assertThrows(IllegalArgumentException.class, () -> OfficeSettlementBatch.open("2026/10", 0));
        assertThrows(IllegalArgumentException.class, () -> FinanceService.periodBounds("2026-13"));
        assertThrows(IllegalArgumentException.class, () -> FinanceService.periodBounds("2026-10,abc"));
        assertThrows(IllegalArgumentException.class, () -> FinanceService.periodBounds("2026-13-01/2026-14-01"));
    }

    @Test
    void multiSegmentPeriodMergesAndSorts() {
        long[][] segments = FinanceService.periodSegments("2026-11,2026-09-01/2026-12-01");
        // 重叠段合并：11月被并入 9.1–12.1，只剩一段
        assertEquals(1, segments.length);
        long[][] two = FinanceService.periodSegments("2026-10,2027-03-01/2027-04-30");
        assertEquals(2, two.length);
        assertTrue(two[0][1] < two[1][0]);
    }

    @Test
    void singleRangeBoundsCoverRange() {
        long[] bounds = FinanceService.periodBounds("2026-09-01/2026-12-01");
        long expectedFrom = java.time.LocalDate.parse("2026-09-01").atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long expectedTo = java.time.LocalDate.parse("2026-12-01").plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() - 1;
        assertEquals(expectedFrom, bounds[0]);
        assertEquals(expectedTo, bounds[1]);
    }

    @Test
    void periodBoundsCoverMonth() {
        long[] bounds = FinanceService.periodBounds("2026-10");
        long expectedFrom = YearMonth.parse("2026-10").atDay(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long expectedTo = YearMonth.parse("2026-11").atDay(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1;
        assertEquals(expectedFrom, bounds[0]);
        assertEquals(expectedTo, bounds[1]);
    }
}
