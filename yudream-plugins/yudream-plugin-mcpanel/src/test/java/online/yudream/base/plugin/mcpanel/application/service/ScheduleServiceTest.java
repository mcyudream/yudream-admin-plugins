package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 计划任务可靠性回归：cron 校验、异常隔离、执行结果如实落库、并发防护、stop。 */
class ScheduleServiceTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private ScheduleService service;
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicReference<RuntimeException> sendFailure = new AtomicReference<>();
    private final CountDownLatch blockSend = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        documents.clear();
        sends.set(0);
        sendFailure.set(null);
        service = new ScheduleService(documents, (instanceId, command) -> {
            sends.incrementAndGet();
            if (sendFailure.get() != null) {
                throw sendFailure.get();
            }
        }, (instanceId, targetCode) -> {
            throw new McpanelBusinessException("schedule.run.failed", 509, "测试桩无备份通道");
        });
    }

    private Map<String, Object> save(String id, String type, Map<String, Object> extra) {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("id", id);
        input.put("instanceId", "inst-1");
        input.put("name", "任务" + id);
        input.put("type", type);
        input.put("payload", "say hi");
        input.putAll(extra);
        return service.save(input);
    }

    // ---------- cron 校验 ----------

    @Test
    void cronValidationRejectsGarbage() {
        List<String> bad = List.of(
                "61 * * * *",      // 分越界
                "* 25 * * *",      // 时越界
                "0 0 0 * *",       // 日越界
                "0 0 * 13 *",      // 月越界
                "* * * * 8",       // 周越界（只允许 0-7）
                "* * * *",         // 段数不足
                "* * * * * *",     // 段数过多
                "a b c d e",       // 非数字
                "*/0 * * * *",     // 步进 0
                "5-2 * * * *"      // 区间倒挂
        );
        for (String expr : bad) {
            McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                    () -> ScheduleService.CronExpr.parse(expr),
                    "应拒绝非法 cron：" + expr);
            assertEquals("invalid-request", error.code());
        }
        // 合法：*/5 步进、月末 31 日（12 月）、闰年 2 月 29 日、2 月的周二、周 7 = 周日。
        ScheduleService.CronExpr.parse("*/5 * * * *");
        ScheduleService.CronExpr.parse("0 0 31 12 *");
        ScheduleService.CronExpr.parse("30 2 29 2 *");
        ScheduleService.CronExpr.parse("0 0 * 2 2");
        ScheduleService.CronExpr.parse("0 0 * * 7");
    }

    @Test
    void saveRejectsNoMatchCron() {
        // 语法合法但永不匹配（2 月无 31 日）：save 层拒绝。
        assertThrows(McpanelBusinessException.class,
                () -> save("sch-cron-bad", "cron", Map.of("cron", "0 0 31 2 *")));
        // 通过校验的正常 cron 可保存，nextRunAt 已推进
        Map<String, Object> record = save("sch-cron-ok", "cron", Map.of("cron", "*/5 * * * *"));
        assertTrue(((Number) record.get("nextRunAt")).longValue() > System.currentTimeMillis());
    }

    @Test
    void nextAfterComputesCorrectNextMinute() {
        // 每日 02:30：从一个确定时刻计算下一次
        Calendar from = Calendar.getInstance();
        from.set(2026, Calendar.MARCH, 10, 5, 0, 0);
        from.set(Calendar.MILLISECOND, 0);
        long next = ScheduleService.CronExpr.parse("30 2 * * *").nextAfter(from.getTimeInMillis());
        Calendar expected = Calendar.getInstance();
        expected.setTimeInMillis(next);
        assertEquals(2026, expected.get(Calendar.YEAR));
        assertEquals(Calendar.MARCH, expected.get(Calendar.MONTH));
        assertEquals(11, expected.get(Calendar.DAY_OF_MONTH));
        assertEquals(2, expected.get(Calendar.HOUR_OF_DAY));
        assertEquals(30, expected.get(Calendar.MINUTE));
    }

    @Test
    void nextAfterFindsLeapDayWithinWindow() {
        Calendar from = Calendar.getInstance();
        from.set(2027, Calendar.FEBRUARY, 28, 12, 0, 0);
        from.set(Calendar.MILLISECOND, 0);
        long next = ScheduleService.CronExpr.parse("0 0 29 2 *").nextAfter(from.getTimeInMillis());
        Calendar expected = Calendar.getInstance();
        expected.setTimeInMillis(next);
        assertEquals(2028, expected.get(Calendar.YEAR));
        assertEquals(Calendar.FEBRUARY, expected.get(Calendar.MONTH));
        assertEquals(29, expected.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    void nextAfterReturnsMinusOneWhenNeverMatches() {
        // 2 月 30-31 日永不存在（parse 只查范围不查语义，nextAfter 兜底 -1）
        assertEquals(-1L, ScheduleService.CronExpr.parse("0 0 30 2 *").nextAfter(1L));
    }

    // ---------- save 不落 null ----------

    @Test
    void saveWithoutRunHistoryStoresZerosNotNulls() {
        // InMemoryDocumentStore 拒绝 null：能保存即说明无 null 字段。
        Map<String, Object> record = save("sch-null", "daily", Map.of("dailyTime", "03:00"));
        assertEquals(0L, ((Number) record.get("lastRunAt")).longValue());
        assertEquals(0L, ((Number) record.get("count")).longValue());
        assertEquals("", String.valueOf(record.get("lastRunStatus")));
        assertEquals("", String.valueOf(record.get("lastError")));
    }

    @Test
    void editPreservesRunHistory() {
        save("sch-hist", "daily", Map.of("dailyTime", "03:00"));
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("id", "sch-hist");
        input.put("instanceId", "inst-1");
        input.put("name", "任务sch-hist");
        input.put("type", "daily");
        input.put("dailyTime", "04:00");
        input.put("payload", "say hi");
        input.put("count", 7);
        input.put("lastRunAt", 12345L);
        input.put("lastRunStatus", "success");
        input.put("lastError", "");
        service.save(input);
        // 再编辑一次（不携带历史字段）：沿用库内值，不被清零。
        Map<String, Object> edit2 = new java.util.LinkedHashMap<>(input);
        edit2.remove("count");
        edit2.remove("lastRunAt");
        edit2.remove("lastRunStatus");
        Map<String, Object> stored = service.save(edit2);
        assertEquals(7L, ((Number) stored.get("count")).longValue());
        assertEquals(12345L, ((Number) stored.get("lastRunAt")).longValue());
        assertEquals("success", String.valueOf(stored.get("lastRunStatus")));
    }

    // ---------- 执行结果如实落库 ----------

    @Test
    void runNowFailureThrowsAndRecordsFailed() {
        save("sch-fail", "interval", Map.of("intervalSeconds", 60));
        sendFailure.set(new McpanelBusinessException("node.offline", 502, "节点未在线"));
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.runNow("sch-fail"));
        assertEquals("node.offline", error.code());
        Map<String, Object> stored = documents.findById("mcpanel_schedules", "sch-fail").orElseThrow();
        assertEquals("failed", String.valueOf(stored.get("lastRunStatus")));
        assertEquals("节点未在线", String.valueOf(stored.get("lastError")));
        // 失败不计入成功次数
        assertEquals(0L, ((Number) stored.get("count")).longValue());
        assertTrue(((Number) stored.get("lastRunAt")).longValue() > 0);
    }

    @Test
    void runNowSuccessIncrementsSuccessCount() {
        save("sch-ok", "interval", Map.of("intervalSeconds", 60));
        Map<String, Object> record = service.runNow("sch-ok");
        assertEquals("success", String.valueOf(record.get("lastRunStatus")));
        assertEquals("", String.valueOf(record.get("lastError")));
        assertEquals(1L, ((Number) record.get("count")).longValue());
    }

    // ---------- tick 隔离与触发 ----------

    @Test
    void tickFiresDueTasksAndIsolatesBrokenRecords() {
        save("sch-due", "interval", Map.of("intervalSeconds", 30));
        // 坏记录：type=interval 但 intervalSeconds 非法字符串（历史脏数据），重算路径不得抛出
        Map<String, Object> broken = new java.util.LinkedHashMap<>();
        broken.put("id", "sch-broken");
        broken.put("instanceId", "inst-1");
        broken.put("name", "坏数据");
        broken.put("type", "interval");
        broken.put("intervalSeconds", "abc");
        broken.put("dailyTime", "");
        broken.put("cron", "");
        broken.put("event", "");
        broken.put("payload", "x");
        broken.put("enabled", true);
        broken.put("count", 0L);
        broken.put("lastRunAt", 0L);
        broken.put("lastRunStatus", "");
        broken.put("lastError", "");
        broken.put("nextRunAt", 0L);
        broken.put("updatedAt", 1L);
        documents.save("mcpanel_schedules", "sch-broken", broken);
        // 到期任务
        Map<String, Object> due = new java.util.LinkedHashMap<>(
                documents.findById("mcpanel_schedules", "sch-due").orElseThrow());
        due.put("nextRunAt", System.currentTimeMillis() - 1000);
        documents.save("mcpanel_schedules", "sch-due", due);

        service.tick();

        assertEquals(1, sends.get());
        Map<String, Object> stored = documents.findById("mcpanel_schedules", "sch-due").orElseThrow();
        assertEquals("success", String.valueOf(stored.get("lastRunAt") == null ? "" : stored.get("lastRunStatus")));
        assertTrue(((Number) stored.get("nextRunAt")).longValue() > System.currentTimeMillis() - 2000);
    }

    @Test
    void deadCronMarkedStoppedWithLastError() {
        // 存量坏 cron（旧版本可能存进去）：nextRunAt=0 触发重算 → -1 停摆 + lastError，且不刷屏重写
        Map<String, Object> legacy = new java.util.LinkedHashMap<>();
        legacy.put("id", "sch-dead");
        legacy.put("instanceId", "inst-1");
        legacy.put("name", "坏cron");
        legacy.put("type", "cron");
        legacy.put("intervalSeconds", 0L);
        legacy.put("dailyTime", "");
        legacy.put("cron", "0 0 31 2 *");
        legacy.put("event", "");
        legacy.put("payload", "say hi");
        legacy.put("enabled", true);
        legacy.put("count", 0L);
        legacy.put("lastRunAt", 0L);
        legacy.put("lastRunStatus", "");
        legacy.put("lastError", "");
        legacy.put("nextRunAt", 0L);
        legacy.put("updatedAt", 1L);
        documents.save("mcpanel_schedules", "sch-dead", legacy);

        service.tick();

        Map<String, Object> stored = documents.findById("mcpanel_schedules", "sch-dead").orElseThrow();
        assertEquals(-1L, ((Number) stored.get("nextRunAt")).longValue());
        assertTrue(String.valueOf(stored.get("lastError")).contains("停摆"));
        assertEquals(0, sends.get());
    }

    @Test
    void eventSchedulesTriggerOnMatchingEvent() {
        save("sch-evt", "event", Map.of("event", "instance.start"));
        service.onInstanceEvent("inst-1", "instance.start");
        assertEquals(1, sends.get());
        // 不匹配的事件不触发
        service.onInstanceEvent("inst-1", "instance.exit");
        assertEquals(1, sends.get());
    }

    // ---------- 并发防护与 stop ----------

    @Test
    void duplicateRunWhileInFlightIsRejected() throws Exception {
        java.util.concurrent.atomic.AtomicInteger entered = new java.util.concurrent.atomic.AtomicInteger();
        ScheduleService blocking = new ScheduleService(documents, (instanceId, command) -> {
            entered.incrementAndGet();
            try {
                blockSend.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, (instanceId, targetCode) -> {
            throw new McpanelBusinessException("schedule.run.failed", 509, "测试桩无备份通道");
        });
        save("sch-block", "interval", Map.of("intervalSeconds", 30));
        Thread runner = new Thread(() -> blocking.runNow("sch-block"));
        runner.start();
        // 等待任务进入 sender（running 门闸被持有）
        for (int i = 0; i < 200 && entered.get() == 0; i++) {
            Thread.sleep(10);
        }
        assertEquals(1, entered.get());
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> blocking.runNow("sch-block"));
        assertEquals("schedule.running", error.code());
        blockSend.countDown();
        runner.join(5000);
        blocking.stop();
    }

    @Test
    void stopPreventsFurtherRuns() {
        save("sch-stop", "interval", Map.of("intervalSeconds", 30));
        service.stop();
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.runNow("sch-stop"));
        assertEquals("plugin.disabled", error.code());
        assertEquals(0, sends.get());
    }
}
