package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 实例计划任务（对标 MCSM Schedule）：
 * type=interval（每隔 N 秒）| daily（每天 HH:mm）| cron（5 段 分 时 日 月 周）| event（实例事件）；
 * action=command（payload=单行控制台命令，默认）| local-backup（宿主备份中心本机导出）
 * | offsite-backup（payload=宿主异地目标编码，到点经宿主备份通道把该实例世界数据推送到对应异地目标）。
 * 面板侧调度线程到期调用 instance.command 或备份触发端口。
 *
 * 可靠性模型：
 * - tick 整体 + 单记录双层异常隔离：任何一条记录损坏都不终止调度线程
 *   （scheduleWithFixedDelay 的任务抛错会吞掉后续所有轮次，必须内层消化）；
 * - 执行结果如实落库：lastRunStatus=success|failed、lastError（截断 200 字）、
 *   lastRunAt；count 只累计成功次数（前端展示「成功次数」）；
 * - 手动运行（runNow）失败原样上抛节点错误（绝不返回成功）；
 * - 同一任务同时至多一次在跑（running 门闸，tick 与 runNow 共用），重复触发被拒；
 * - cron 在保存时完整校验（字段范围/步进/列表/区间 + 未来 4 年有匹配），
 *   无匹配（如 2 月 31 日）直接拒绝；存量坏数据 nextRunAt=-1 停摆并写 lastError；
 * - stop() 关停调度线程（有界等待）；关停后 fire 一律拒绝，不残留外部调用。
 */
public class ScheduleService {

    private static final String COLLECTION = "mcpanel_schedules";
    private static final Pattern ID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9-]{0,63}$");
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");
    /** lastError 落库截断长度。 */
    private static final int LAST_ERROR_MAX = 200;
    /** cron 向前扫描窗口：4 年（覆盖 2 月 29 日）。 */
    private static final int CRON_SCAN_MINUTES = 4 * 366 * 24 * 60;

    public interface CommandSender {
        void send(String instanceId, String command);
    }

    /**
     * 异地备份触发端口：由宿主备份通道（SPI PluginBackupOperations）实现，
     * 返回宿主任务 id；宿主 SPI 过旧时为 null，对应任务执行报「无通道」。
     */
    public interface BackupTrigger {
        String trigger(String instanceId, String targetCode);
    }

    private final PluginDocumentStore documents;
    private final CommandSender sender;
    private final BackupTrigger backupTrigger;
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean stopped;
    /** 在跑任务门闸（id → 在跑）：tick 与 runNow 共用，防并发重复执行。 */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public ScheduleService(PluginDocumentStore documents, CommandSender sender, BackupTrigger backupTrigger) {
        this.documents = documents;
        this.sender = sender;
        this.backupTrigger = backupTrigger;
    }

    public synchronized void start() {
        stopped = false;
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcpanel-schedule");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::tick, 30, 30, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        stopped = true;
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current == null) {
            return;
        }
        current.shutdownNow();
        try {
            if (!current.awaitTermination(2, TimeUnit.SECONDS)) {
                System.err.println("[mcpanel] 计划任务线程未在 2s 内退出");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    public Map<String, Object> page(String instanceId, int page, int size) {
        List<Map<String, Object>> filtered = loadAll().stream()
                .filter(item -> instanceId == null || instanceId.isBlank()
                        || instanceId.equals(String.valueOf(item.get("instanceId"))))
                .sorted((a, b) -> String.valueOf(a.getOrDefault("name", ""))
                        .compareToIgnoreCase(String.valueOf(b.getOrDefault("name", ""))))
                .toList();
        int pageNo = Math.max(1, page);
        int pageSize = size < 1 ? 20 : Math.min(size, 100);
        int from = Math.min((pageNo - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", filtered.subList(from, to));
        result.put("total", filtered.size());
        result.put("page", pageNo);
        result.put("size", pageSize);
        return result;
    }

    public Map<String, Object> save(Map<String, Object> input) {
        String instanceId = text(input.get("instanceId"));
        if (instanceId.isBlank()) {
            throw McpanelBusinessException.invalid("缺少实例 ID");
        }
        String name = text(input.get("name"));
        if (name.isBlank() || name.length() > 64) {
            throw McpanelBusinessException.invalid("任务名称长度应为 1-64");
        }
        String type = text(input.getOrDefault("type", "interval"));
        if (!"interval".equals(type) && !"daily".equals(type) && !"cron".equals(type) && !"event".equals(type)) {
            throw McpanelBusinessException.invalid("类型仅支持 interval / daily / cron / event");
        }
        long intervalSeconds = 0;
        String dailyTime = "";
        String cron = "";
        String event = "";
        if ("interval".equals(type)) {
            Object raw = input.get("intervalSeconds");
            try {
                intervalSeconds = raw instanceof Number number ? number.longValue() : Long.parseLong(text(raw));
            } catch (NumberFormatException error) {
                throw McpanelBusinessException.invalid("间隔秒数必须为整数");
            }
            if (intervalSeconds < 30 || intervalSeconds > 7 * 24 * 3600) {
                throw McpanelBusinessException.invalid("间隔需在 30 秒至 7 天之间");
            }
        }
        else if ("daily".equals(type)) {
            dailyTime = text(input.get("dailyTime"));
            if (!TIME.matcher(dailyTime).matches()) {
                throw McpanelBusinessException.invalid("每日时间格式 HH:mm");
            }
        }
        else if ("cron".equals(type)) {
            cron = text(input.get("cron"));
            // 保存期完整校验：字段语法 + 数值范围 + 未来 4 年存在匹配（无匹配如 2/31 直接拒绝）。
            long firstRun = CronExpr.parse(cron).nextAfter(System.currentTimeMillis());
            if (firstRun < 0) {
                throw McpanelBusinessException.invalid("cron 在未来 4 年内无匹配执行时间（如 2 月 31 日），请修正表达式");
            }
        }
        else {
            event = text(input.get("event"));
            if (!List.of("instance.start", "instance.stop", "instance.exit", "instance.create").contains(event)) {
                throw McpanelBusinessException.invalid("事件类型需为 instance.start/stop/exit/create");
            }
        }
        String action = text(input.getOrDefault("action", "command"));
        if (!"command".equals(action) && !"offsite-backup".equals(action) && !"local-backup".equals(action)) {
            throw McpanelBusinessException.invalid("动作仅支持 command / local-backup / offsite-backup");
        }
        String payload;
        if ("offsite-backup".equals(action)) {
            // 异地备份：payload 字段复用为宿主异地目标编码（备份中心配置的 code）。
            payload = text(input.get("payload"));
            if (!Pattern.matches("[a-z0-9][a-z0-9-]{0,63}", payload)) {
                throw McpanelBusinessException.invalid("异地目标编码须为小写字母/数字/连字符（在宿主备份中心配置）");
            }
        } else if ("local-backup".equals(action)) {
            // 本地备份：经宿主备份通道做本机导出（targetCode 空），无需命令/目标。
            payload = "";
        } else {
            payload = text(input.get("payload"));
            if (payload.isBlank() || payload.length() > 400 || payload.contains("\n")) {
                throw McpanelBusinessException.invalid("命令必须为单行且不超过 400 字符");
            }
        }
        boolean enabled = !Boolean.FALSE.equals(input.get("enabled"));
        String id = text(input.get("id"));
        if (id.isBlank()) {
            id = "sch-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        else if (!ID.matcher(id).matches()) {
            throw McpanelBusinessException.invalid("任务 ID 格式无效");
        }
        long now = System.currentTimeMillis();
        // 编辑时保留历史执行信息（count/lastRun*），除非请求显式携带（防止文档存储 null 与误清零）。
        Map<String, Object> existing = id == null ? null : documents.findById(COLLECTION, id).orElse(null);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("instanceId", instanceId);
        record.put("name", name);
        record.put("type", type);
        record.put("intervalSeconds", intervalSeconds);
        record.put("dailyTime", dailyTime);
        record.put("cron", cron);
        record.put("event", event);
        record.put("action", action);
        record.put("payload", payload);
        record.put("enabled", enabled);
        record.put("count", historyField(input, existing, "count", 0L));
        record.put("lastRunAt", historyField(input, existing, "lastRunAt", 0L));
        record.put("lastRunStatus", historyField(input, existing, "lastRunStatus", ""));
        record.put("lastError", historyField(input, existing, "lastError", ""));
        record.put("nextRunAt", "event".equals(type) ? 0L
                : nextRun(type, intervalSeconds, dailyTime, cron, now));
        record.put("updatedAt", now);
        documents.save(COLLECTION, id, record);
        return record;
    }

    public void delete(String id) {
        if (id == null || id.isBlank()) {
            throw McpanelBusinessException.invalid("任务 ID 不能为空");
        }
        documents.delete(COLLECTION, id);
    }

    /** 手动运行：失败原样上抛（含节点错误码），成功返回更新后的任务记录。 */
    public Map<String, Object> runNow(String id) {
        if (stopped) {
            throw new McpanelBusinessException("plugin.disabled", 409, "插件正在停止，无法执行任务");
        }
        Map<String, Object> record = documents.findById(COLLECTION, id)
                .orElseThrow(() -> McpanelBusinessException.notFound("计划任务不存在"));
        if (!Boolean.TRUE.equals(record.get("enabled"))) {
            throw McpanelBusinessException.invalid("任务未启用");
        }
        if (!running.add(String.valueOf(record.get("id")))) {
            throw new McpanelBusinessException("schedule.running", 409, "任务正在执行中，请稍后再试");
        }
        try {
            fire(record);
        } finally {
            running.remove(String.valueOf(record.get("id")));
        }
        if (!"success".equals(String.valueOf(record.get("lastRunStatus")))) {
            // fire 内部已把原始节点错误记录进 lastError；手动运行必须如实失败。
            throw new McpanelBusinessException("schedule.run.failed", 502,
                    "任务执行失败：" + String.valueOf(record.getOrDefault("lastError", "")));
        }
        return record;
    }

    void tick() {
        if (stopped) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            for (Map<String, Object> record : loadAll()) {
                // 单记录异常隔离：坏数据（类型/数值异常等）不影响其余任务与下一轮调度。
                try {
                    tickRecord(record, now);
                } catch (RuntimeException ignored) {
                    // 跳过该记录，下一轮重试
                }
            }
        } catch (RuntimeException ignored) {
            // tick 主体绝不抛出：scheduleWithFixedDelay 任务抛错会终止后续全部轮次
        }
    }

    private void tickRecord(Map<String, Object> record, long now) {
        if (!Boolean.TRUE.equals(record.get("enabled"))) {
            return;
        }
        if ("event".equals(text(record.get("type")))) {
            return;
        }
        Object next = record.get("nextRunAt");
        long nextAt = next instanceof Number number ? number.longValue() : 0L;
        if (nextAt < 0) {
            // 停摆（cron 无匹配等）：等待人工修复，不再反复重算写库。
            return;
        }
        if (nextAt == 0) {
            long recomputed = nextRun(text(record.get("type")), number(record.get("intervalSeconds")),
                    text(record.get("dailyTime")), text(record.get("cron")), now);
            record.put("nextRunAt", recomputed);
            if (recomputed < 0) {
                record.put("lastError", "计划时间无法计算（cron 无匹配），任务已停摆");
            }
            documents.save(COLLECTION, String.valueOf(record.get("id")), record);
            return;
        }
        if (nextAt > now) {
            return;
        }
        if (!running.add(String.valueOf(record.get("id")))) {
            // 手动运行在跑：跳过本轮，防同一任务并发重复执行。
            return;
        }
        try {
            fire(record);
        } finally {
            running.remove(String.valueOf(record.get("id")));
        }
    }

    /** 事件触发：实例状态变化时调用（type=event 且 event 匹配）。 */
    public void onInstanceEvent(String instanceId, String eventName) {
        if (stopped || instanceId == null || eventName == null) {
            return;
        }
        try {
            for (Map<String, Object> record : loadAll()) {
                if (!Boolean.TRUE.equals(record.get("enabled"))) {
                    continue;
                }
                if (!"event".equals(text(record.get("type")))) {
                    continue;
                }
                if (!eventName.equals(text(record.get("event")))) {
                    continue;
                }
                if (!instanceId.equals(text(record.get("instanceId")))) {
                    continue;
                }
                if (!running.add(String.valueOf(record.get("id")))) {
                    continue;
                }
                try {
                    fire(record);
                } finally {
                    running.remove(String.valueOf(record.get("id")));
                }
            }
        } catch (RuntimeException ignored) {
            // 事件触发不抛出（事件协调器通道内调用）
        }
    }

    /**
     * 执行一次任务并如实记录结果：
     * - 成功：lastRunStatus=success、count+1（成功次数口径）、lastError 清空、推进 nextRunAt；
     * - 失败：lastRunStatus=failed、lastError=错误摘要（原始异常原样保留给 runNow 上抛）；
     * - lastRunAt 两种情况都刷新；非 event 类型推进 nextRunAt（无法计算时 -1 停摆）。
     * 调用方持有 running 门闸。返回是否成功。
     */
    private boolean fire(Map<String, Object> record) {
        String id = String.valueOf(record.get("id"));
        String instanceId = text(record.get("instanceId"));
        String command = text(record.get("payload"));
        long now = System.currentTimeMillis();
        RuntimeException failure = null;
        try {
            if ("offsite-backup".equals(text(record.get("action")))) {
                if (backupTrigger == null) {
                    throw new McpanelBusinessException("schedule.run.failed", 509,
                            "宿主无备份触发通道（宿主 SPI 过旧或未升级），无法异地备份");
                }
                String jobId = backupTrigger.trigger(instanceId, command);
                record.put("lastJobId", jobId);
            }
            else if ("local-backup".equals(text(record.get("action")))) {
                // 本地备份：走同一宿主备份通道，targetCode 空 = 备份中心本机导出。
                if (backupTrigger == null) {
                    throw new McpanelBusinessException("schedule.run.failed", 509,
                            "宿主无备份触发通道（宿主 SPI 过旧或未升级），无法本地备份");
                }
                String jobId = backupTrigger.trigger(instanceId, "");
                record.put("lastJobId", jobId);
            }
            else {
                sender.send(instanceId, command);
            }
            record.put("lastRunStatus", "success");
            record.put("lastError", "");
            record.put("count", number(record.get("count")) + 1);
        } catch (RuntimeException | LinkageError error) {
            failure = error instanceof RuntimeException runtime ? runtime
                    : new McpanelBusinessException("schedule.run.failed", 502, String.valueOf(error.getMessage()));
            record.put("lastRunStatus", "failed");
            record.put("lastError", truncate(error.getMessage() == null ? "执行失败" : error.getMessage()));
        }
        record.put("lastRunAt", now);
        if (!"event".equals(text(record.get("type")))) {
            long next = nextRun(text(record.get("type")), number(record.get("intervalSeconds")),
                    text(record.get("dailyTime")), text(record.get("cron")), now);
            record.put("nextRunAt", next);
            if (next < 0) {
                record.put("lastError", "计划时间无法计算（cron 无匹配），任务已停摆");
            }
        }
        try {
            documents.save(COLLECTION, id, record);
        } catch (RuntimeException ignored) {
            // 结果落库失败不影响本次执行语义（下一轮会重算推进）
        }
        if (failure != null) {
            throw failure;
        }
        return true;
    }

    // ---------- 下次执行时间 ----------

    private static long nextRun(String type, long intervalSeconds, String dailyTime, String cron, long from) {
        if ("interval".equals(type) && intervalSeconds > 0) {
            return from + intervalSeconds * 1000L;
        }
        if ("daily".equals(type) && TIME.matcher(dailyTime).matches()) {
            String[] parts = dailyTime.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(from);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            if (cal.getTimeInMillis() <= from) {
                cal.add(Calendar.DAY_OF_MONTH, 1);
            }
            return cal.getTimeInMillis();
        }
        if ("cron".equals(type)) {
            try {
                return CronExpr.parse(cron).nextAfter(from);
            } catch (McpanelBusinessException error) {
                // 存量坏表达式：停摆（-1），由 lastError 说明。
                return -1L;
            }
        }
        return from + 3600_000L;
    }

    // ---------- cron（分 时 日 月 周） ----------

    /**
     * 5 段 cron 的解析与匹配（本面板唯一实现）：
     * 支持「*」「?」数字、列表(,)、区间(-)、步进(*&#47;n 与 a-b&#47;n)；范围 分 0-59 / 时 0-23 /
     * 日 1-31 / 月 1-12 / 周 0-7（0 与 7 都是周日）；日与周同时受限时按标准
     * cron 取 OR，月恒取 AND。nextAfter 向前逐分钟扫描（月/时跳跃优化），
     * 4 年窗口无匹配返回 -1。
     */
    static final class CronExpr {

        private final boolean[] minute;
        private final boolean[] hour;
        private final boolean[] dayOfMonth;
        private final boolean[] month;
        private final boolean[] dayOfWeek;
        private final boolean domRestricted;
        private final boolean dowRestricted;

        private CronExpr(boolean[] minute, boolean[] hour, boolean[] dayOfMonth, boolean[] month,
                         boolean[] dayOfWeek, boolean domRestricted, boolean dowRestricted) {
            this.minute = minute;
            this.hour = hour;
            this.dayOfMonth = dayOfMonth;
            this.month = month;
            this.dayOfWeek = dayOfWeek;
            this.domRestricted = domRestricted;
            this.dowRestricted = dowRestricted;
        }

        static CronExpr parse(String expr) {
            if (expr == null || expr.isBlank()) {
                throw McpanelBusinessException.invalid("cron 表达式不能为空");
            }
            String[] fields = expr.trim().split("\\s+");
            if (fields.length != 5) {
                throw McpanelBusinessException.invalid("cron 需为 5 段：分 时 日 月 周");
            }
            boolean[] minute = parseField(fields[0], 0, 59, "分");
            boolean[] hour = parseField(fields[1], 0, 23, "时");
            boolean[] dom = parseField(fields[2], 1, 31, "日");
            boolean[] month = parseField(fields[3], 1, 12, "月");
            // 周：0-7，7 视为 0（周日）。
            boolean[] dowRaw = parseField(fields[4], 0, 7, "周");
            boolean[] dow = new boolean[7];
            for (int value = 0; value < dowRaw.length; value++) {
                if (dowRaw[value]) {
                    dow[value % 7] = true;
                }
            }
            boolean domRestricted = !isWildcard(fields[2]);
            boolean dowRestricted = !isWildcard(fields[4]);
            return new CronExpr(minute, hour, dom, month, dow, domRestricted, dowRestricted);
        }

        private static boolean isWildcard(String field) {
            return "*".equals(field.trim()) || "?".equals(field.trim());
        }

        /** 解析单字段为布尔命中表（含 min 下标占位：dayOfMonth/month 从 1 起，0 位恒 false）。 */
        private static boolean[] parseField(String field, int min, int max, String label) {
            boolean[] hits = new boolean[max + 1];
            for (String part : field.split(",")) {
                String token = part.trim();
                if (token.isEmpty()) {
                    throw McpanelBusinessException.invalid("cron " + label + " 字段含空段");
                }
                int step = 1;
                int rangeEnd;
                int rangeStart;
                if (token.startsWith("*/")) {
                    step = parseStep(token.substring(2), label);
                    rangeStart = min;
                    rangeEnd = max;
                } else if (token.contains("/")) {
                    int slash = token.indexOf('/');
                    String base = token.substring(0, slash);
                    step = parseStep(token.substring(slash + 1), label);
                    if (base.contains("-")) {
                        int dash = base.indexOf('-');
                        rangeStart = parseValue(base.substring(0, dash), min, max, label);
                        rangeEnd = parseValue(base.substring(dash + 1), min, max, label);
                    } else {
                        rangeStart = parseValue(base, min, max, label);
                        rangeEnd = max;
                    }
                } else if (token.contains("-")) {
                    int dash = token.indexOf('-');
                    rangeStart = parseValue(token.substring(0, dash), min, max, label);
                    rangeEnd = parseValue(token.substring(dash + 1), min, max, label);
                } else if ("*".equals(token) || "?".equals(token)) {
                    rangeStart = min;
                    rangeEnd = max;
                } else {
                    rangeStart = parseValue(token, min, max, label);
                    rangeEnd = rangeStart;
                }
                if (rangeStart > rangeEnd) {
                    throw McpanelBusinessException.invalid("cron " + label + " 字段区间起点需小于等于终点");
                }
                for (int value = rangeStart; value <= rangeEnd; value += step) {
                    if (value >= min && value <= max) {
                        hits[value] = true;
                    }
                }
            }
            return hits;
        }

        private static int parseStep(String raw, String label) {
            try {
                int step = Integer.parseInt(raw.trim());
                if (step < 1) {
                    throw new NumberFormatException();
                }
                return step;
            } catch (NumberFormatException error) {
                throw McpanelBusinessException.invalid("cron " + label + " 字段步进必须为正整数");
            }
        }

        private static int parseValue(String raw, int min, int max, String label) {
            try {
                int value = Integer.parseInt(raw.trim());
                if (value < min || value > max) {
                    throw McpanelBusinessException.invalid(
                            "cron " + label + " 字段取值需在 " + min + "-" + max + " 之间");
                }
                return value;
            } catch (NumberFormatException error) {
                throw McpanelBusinessException.invalid("cron " + label + " 字段含非法数字");
            }
        }

        /** from 之后（不含 from）的下一个匹配时间；4 年窗口无匹配返回 -1。 */
        long nextAfter(long from) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(from);
            cal.add(Calendar.MINUTE, 1);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            for (int i = 0; i < CRON_SCAN_MINUTES; i++) {
                if (!month[cal.get(Calendar.MONTH) + 1]) {
                    // 整月不可能命中：跳到下月 1 日 00:00。
                    cal.set(Calendar.DAY_OF_MONTH, 1);
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.add(Calendar.MONTH, 1);
                    continue;
                }
                if (!dayMatches(cal)) {
                    // 当天不可能命中：跳到明天 00:00。
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.add(Calendar.DAY_OF_MONTH, 1);
                    continue;
                }
                if (!hour[cal.get(Calendar.HOUR_OF_DAY)]) {
                    // 整点不可能命中：跳到下一小时 00 分。
                    cal.set(Calendar.MINUTE, 0);
                    cal.add(Calendar.HOUR_OF_DAY, 1);
                    continue;
                }
                if (minute[cal.get(Calendar.MINUTE)]) {
                    return cal.getTimeInMillis();
                }
                cal.add(Calendar.MINUTE, 1);
            }
            return -1L;
        }

        private boolean dayMatches(Calendar cal) {
            boolean monthOk = month[cal.get(Calendar.MONTH) + 1];
            boolean domOk = dayOfMonth[cal.get(Calendar.DAY_OF_MONTH)];
            boolean dowOk = dayOfWeek[cal.get(Calendar.DAY_OF_WEEK) - 1];
            if (domRestricted && dowRestricted) {
                // 标准 cron：日/周均受限时取 OR。
                return monthOk && (domOk || dowOk);
            }
            return monthOk && domOk && dowOk;
        }
    }

    // ---------- 存取辅助 ----------

    private List<Map<String, Object>> loadAll() {
        List<Map<String, Object>> all = new ArrayList<>();
        int p = 1;
        List<Map<String, Object>> batch;
        do {
            batch = documents.findAll(COLLECTION, p, 200);
            all.addAll(batch);
            p++;
        } while (batch.size() == 200);
        return all;
    }

    /** 编辑保留历史字段：请求显式携带非空值优先，否则沿用库内值，缺省 fallback（杜绝 null 落库）。 */
    private static Object historyField(Map<String, Object> input, Map<String, Object> existing,
                                       String key, Object fallback) {
        Object provided = input.get(key);
        if (provided != null) {
            if ("count".equals(key) || "lastRunAt".equals(key)) {
                return number(provided);
            }
            return text(provided);
        }
        if (existing != null && existing.get(key) != null) {
            return existing.get(key);
        }
        return fallback;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private static String truncate(String message) {
        String cleaned = message == null ? "" : message.replace("\n", " ").replace("\r", " ").trim();
        return cleaned.length() <= LAST_ERROR_MAX ? cleaned : cleaned.substring(0, LAST_ERROR_MAX);
    }
}
