package online.yudream.base.plugin.aioffice;

import online.yudream.base.plugin.aioffice.application.FinanceService;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeSettlementBatch;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
