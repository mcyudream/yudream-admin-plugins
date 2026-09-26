package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 性能历史环形窗口（实例维度 + 节点维度）：
 * - 数据源：节点 stats 快照（约 5s/节点）经协调器 stats 通道喂入，按 30s 采样一点；
 *   实例维度取 containers[] 里 running 系容器，节点维度取整机 cpu/mem；
 * - 缓存：内存环形缓冲（24h@30s = 2880 点/主体）+ 文档存储每 60s 刷脏（面板重启后惰性回读）；
 * - 过期自动失效：写入与查询均按 24h 窗口裁剪，实例/节点删除时由调用方 evict 清理。
 *
 * <p><b>采集是后台常驻的</b>：只依赖节点连接上报，与浏览器是否打开页面无关——
 * 总览、节点列表、实例详情都从这里读历史，因此刷新页面/面板重启后曲线仍连续，
 * 不再出现「进页面才开始攒点」。
 *
 * 设计取舍：插件环境无 Redis 契约（宿主未暴露、禁止私连宿主内部组件），文档存储
 * 轮换即可满足「缓存 + TTL」语义且零部署；写放大为每活跃主体每分钟 1 次文档写。
 */
public class MetricsHistoryService implements AutoCloseable {

    private static final String COLLECTION = "mcpanel_metrics";
    /** 节点维度历史（整机 CPU/内存）独立集合：与实例维度同节奏、互不影响。 */
    private static final String NODE_COLLECTION = "mcpanel_node_metrics";
    /** 采样间隔（stats 5s 级上报，30s 落一点足够趋势图）。 */
    private static final long SAMPLE_MS = 30_000L;
    /** 刷盘间隔。 */
    private static final long FLUSH_MS = 60_000L;
    /** 保留窗口 = 过期 TTL。 */
    private static final long RETAIN_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_POINTS = (int) (RETAIN_MS / SAMPLE_MS) + 8;

    private final PluginDocumentStore documents;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, Series> seriesByInstance = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Series> seriesByNode = new ConcurrentHashMap<>();

    /** 点 = [epochMs, 数值…]；实例维度 [at,cpu,memUsed]，节点维度 [at,cpu,memUsed,memTotal]。 */
    static final class Series {
        final ArrayDeque<double[]> points = new ArrayDeque<>();
        volatile long lastSampleAt;
        volatile boolean dirty;
        volatile boolean loaded;
    }

    public MetricsHistoryService(PluginDocumentStore documents) {
        this.documents = documents;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mcpanel-metrics-history");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::flushDirty, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
    }

    /** 协调器 stats 通道喂入：只采样运行系容器，按实例节流到 SAMPLE_MS。 */
    public void onStats(String nodeId, Map<String, Object> stats) {
        if (stats == null || !(stats.get("containers") instanceof List<?> rows)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> record) || record.get("instanceId") == null) {
                continue;
            }
            String instanceId = String.valueOf(record.get("instanceId"));
            String state = String.valueOf(record.get("state"));
            if (!"running".equals(state)) {
                continue; // 停机不采样：曲线自然断开，由前端按时间轴处理
            }
            double cpu = record.get("cpuPercent") instanceof Number number ? number.doubleValue() : 0d;
            double mem = record.get("memUsedMb") instanceof Number number ? number.doubleValue() : 0d;
            Series series = ensureLoaded(seriesByInstance, COLLECTION, instanceId);
            synchronized (series) {
                if (now - series.lastSampleAt < SAMPLE_MS) {
                    continue;
                }
                series.lastSampleAt = now;
                series.points.addLast(new double[]{now,
                        Math.max(0d, Math.min(100d, cpu)), Math.max(0d, mem)});
                series.dirty = true;
                trim(series, now);
            }
        }
    }

    /**
     * 节点维度喂入（整机 CPU/内存）：与实例维度同一 stats 流、同一 30s 节流。
     * 节点未上报 cpu/mem 时跳过（不落无意义的 0 点）。
     */
    public void onNodeStats(String nodeId, Map<String, Object> stats) {
        if (nodeId == null || nodeId.isBlank() || stats == null) {
            return;
        }
        Double cpu = numberOrNull(stats.get("cpuPercent"));
        Double memUsed = numberOrNull(stats.get("memUsedMb"));
        if (cpu == null && memUsed == null) {
            return;
        }
        Double memTotal = numberOrNull(stats.get("memTotalMb"));
        long now = System.currentTimeMillis();
        Series series = ensureLoaded(seriesByNode, NODE_COLLECTION, nodeId);
        synchronized (series) {
            if (now - series.lastSampleAt < SAMPLE_MS) {
                return;
            }
            series.lastSampleAt = now;
            series.points.addLast(new double[]{now, clampPercent(cpu), nonNegative(memUsed), nonNegative(memTotal)});
            series.dirty = true;
            trim(series, now);
        }
    }

    /** 查询窗口内点位（懒回读文档）；实例从未运行返回空。 */
    public Map<String, Object> view(String instanceId, long windowMs) {
        return Map.of("instanceId", instanceId, "windowMs", window(windowMs),
                "sampleMs", SAMPLE_MS, "points", points(instanceId, windowMs));
    }

    /** 实例窗口点：总览等页面直接复用（不发外部请求，首次访问懒回读文档）。 */
    public List<double[]> points(String instanceId, long windowMs) {
        return windowed(seriesByInstance, COLLECTION, instanceId, windowMs);
    }

    /** 节点窗口点：节点管理页与总览的整机趋势。 */
    public List<double[]> nodePoints(String nodeId, long windowMs) {
        return windowed(seriesByNode, NODE_COLLECTION, nodeId, windowMs);
    }

    /** 实例删除时清理内存与文档。 */
    public void evict(String instanceId) {
        evictFrom(seriesByInstance, COLLECTION, instanceId);
    }

    /** 节点删除时清理内存与文档。 */
    public void evictNode(String nodeId) {
        evictFrom(seriesByNode, NODE_COLLECTION, nodeId);
    }

    private long window(long windowMs) {
        return Math.min(Math.max(windowMs, 60_000L), RETAIN_MS);
    }

    private List<double[]> windowed(ConcurrentHashMap<String, Series> map, String collection,
                                    String key, long windowMs) {
        long window = window(windowMs);
        long now = System.currentTimeMillis();
        Series series = ensureLoaded(map, collection, key);
        List<double[]> points = new ArrayList<>();
        if (series != null) {
            synchronized (series) {
                for (double[] point : series.points) {
                    if (point[0] >= now - window) {
                        points.add(point);
                    }
                }
            }
        }
        return points;
    }

    private void evictFrom(ConcurrentHashMap<String, Series> map, String collection, String key) {
        map.remove(key);
        try {
            documents.delete(collection, key);
        }
        catch (RuntimeException ignored) {
            // 文档缺失等：清理尽力而为。
        }
    }

    /**
     * 取（必要时创建并回读磁盘的）内存环。
     *
     * <p>关键：**采样前必须回读**——面板重启后内存环是空的，若不先并入磁盘点，
     * 首次刷盘会用只有一两个新点的快照覆盖掉已落库的整段历史（历史"凭空消失"的根因）。
     */
    private Series ensureLoaded(ConcurrentHashMap<String, Series> map, String collection, String key) {
        Series series = map.computeIfAbsent(key, ignored -> new Series());
        if (!series.loaded) {
            synchronized (series) {
                if (!series.loaded) {
                    loadInto(series, collection, key);
                }
            }
        }
        return series;
    }

    /** 回读磁盘点并入环（内存点优先；按时间戳只保留比内存首点更早的历史，再裁窗口）。 */
    private void loadInto(Series series, String collection, String key) {
        List<double[]> persisted = parsePoints(documents.findById(collection, key).orElse(null));
        if (!persisted.isEmpty()) {
            double firstInMemory = series.points.isEmpty()
                    ? Double.MAX_VALUE : series.points.getFirst()[0];
            for (int index = persisted.size() - 1; index >= 0; index--) {
                double[] point = persisted.get(index);
                if (point[0] < firstInMemory) {
                    series.points.addFirst(point);
                }
            }
            if (!series.points.isEmpty()) {
                series.lastSampleAt = Math.max(series.lastSampleAt, (long) series.points.getLast()[0]);
            }
            trim(series, System.currentTimeMillis());
        }
        series.loaded = true;
    }

    /** 文档 → 点列表（只认「时间戳 + 至少两个数值」的行；超期点丢弃）。 */
    private static List<double[]> parsePoints(Map<String, Object> doc) {
        List<double[]> points = new ArrayList<>();
        if (doc == null || !(doc.get("points") instanceof List<?> rows)) {
            return points;
        }
        long now = System.currentTimeMillis();
        for (Object row : rows) {
            if (!(row instanceof List<?> point) || point.size() < 3) {
                continue;
            }
            double[] values = new double[point.size()];
            boolean valid = true;
            for (int index = 0; index < point.size(); index++) {
                if (point.get(index) instanceof Number value) {
                    values[index] = value.doubleValue();
                }
                else {
                    valid = false;
                    break;
                }
            }
            if (valid && now - values[0] <= RETAIN_MS) {
                points.add(values);
            }
        }
        return points;
    }

    private void trim(Series series, long now) {
        while (series.points.size() > MAX_POINTS || (!series.points.isEmpty()
                && series.points.getFirst()[0] < now - RETAIN_MS)) {
            series.points.removeFirst();
        }
    }

    private void flushDirty() {
        flush(seriesByInstance, COLLECTION);
        flush(seriesByNode, NODE_COLLECTION);
    }

    private void flush(ConcurrentHashMap<String, Series> map, String collection) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Series> item : map.entrySet()) {
            Series series = item.getValue();
            boolean shouldWrite;
            List<List<Number>> snapshot = new ArrayList<>();
            synchronized (series) {
                shouldWrite = series.dirty;
                if (shouldWrite) {
                    for (double[] point : series.points) {
                        // 文档存储统一数字列表形态（与回读解析一致）。
                        List<Number> numbers = new ArrayList<>(point.length);
                        for (double value : point) {
                            numbers.add(value);
                        }
                        snapshot.add(numbers);
                    }
                }
                series.dirty = false;
            }
            if (!shouldWrite) {
                continue;
            }
            try {
                documents.save(collection, item.getKey(), Map.of(
                        "points", snapshot,
                        "updatedAt", now));
            }
            catch (RuntimeException ignored) {
                series.dirty = true; // 下轮重试
            }
        }
    }

    private static Double numberOrNull(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static double clampPercent(Double value) {
        return value == null ? 0d : Math.max(0d, Math.min(100d, value));
    }

    private static double nonNegative(Double value) {
        return value == null ? 0d : Math.max(0d, value);
    }

    @Override
    public void close() {
        flushDirty();
        scheduler.shutdown();
    }
}
