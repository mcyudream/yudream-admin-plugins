package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 联动状态心跳：定期把已绑定子服的实例状态（含人数上限）重新回推给 minecraft-server。
 *
 * <p>回推原本只在实例状态发生变化时发生，而 minecraft-server 侧用「面板确认实例在运行」
 * 作为线路探测全部不可达时的在线兜底依据（面板与节点同机部署时，面板经公网地址访问
 * 自己的游戏端口常被网关 NAT 回环策略拒绝，玩家从外网访问却正常）。回推一旦过期
 * （面板重启后内存态为空、实例长时间无状态变化）兜底即失效，列表又回到「明明在跑却显示离线」。
 * 因此这里按周期重推，保证兜底依据持续新鲜。
 *
 * <p>同时顺带回推实例 {@code server.properties} 的 {@code max-players}：ping 不可达时
 * 面板侧仍能给出人数上限，列表按「在线/上限」展示。配置文件读取带长 TTL 缓存
 * （上限极少变更），读不到按 0 处理并短 TTL 重试，避免每个周期都去打节点。
 */
public class MinecraftLinkHeartbeat implements AutoCloseable {

    private static final long INTERVAL_SECONDS = 60;
    /** 人数上限缓存：命中期间不再读节点（配置极少变更）。 */
    private static final long MAX_PLAYERS_TTL_MS = 10L * 60 * 1000;
    /** 读取失败后的短 TTL：下一轮再试，避免每个周期都打节点。 */
    private static final long MAX_PLAYERS_RETRY_MS = 60 * 1000;

    private final McpanelInstanceRepository instances;
    private final MinecraftLinkService link;
    private final ServerConfigService configs;
    private final ConcurrentHashMap<String, CachedMaxPlayers> maxPlayersCache = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    private record CachedMaxPlayers(int maxPlayers, long expiresAtMs) {
    }

    public MinecraftLinkHeartbeat(McpanelInstanceRepository instances, MinecraftLinkService link) {
        this(instances, link, null);
    }

    public MinecraftLinkHeartbeat(McpanelInstanceRepository instances, MinecraftLinkService link,
                                  ServerConfigService configs) {
        this.instances = instances;
        this.link = link;
        this.configs = configs;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mcpanel-link-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::pulseSafely, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** 单次心跳：只回推已绑定子服的实例；链接不可用/异常只日志，不影响面板主流程。 */
    void pulseSafely() {
        try {
            for (McpanelInstance instance : instances.findAll()) {
                if (instance.mcServerId() == null || instance.mcServerId().isBlank()) {
                    continue;
                }
                link.writebackState(instance, maxPlayersOf(instance));
            }
        } catch (RuntimeException | Error error) {
            System.err.println("[mcpanel] 联动状态心跳失败: " + error);
        }
    }

    /** 实例人数上限（server.properties 的 max-players）：带缓存；读不到按 0 短 TTL 重试。 */
    int maxPlayersOf(McpanelInstance instance) {
        long now = System.currentTimeMillis();
        CachedMaxPlayers cached = maxPlayersCache.get(instance.id());
        if (cached != null && cached.expiresAtMs() > now) {
            return cached.maxPlayers();
        }
        int maxPlayers = 0;
        if (configs != null) {
            try {
                Map<String, Object> view = configs.view("system:link-heartbeat", instance.id(),
                        ServerConfigService.DEFAULT_PATH);
                Object properties = view.get("properties");
                if (properties instanceof Map<?, ?> map) {
                    Object raw = map.get("max-players");
                    if (raw != null) {
                        maxPlayers = Math.max(Integer.parseInt(String.valueOf(raw).trim()), 0);
                    }
                }
                maxPlayersCache.put(instance.id(), new CachedMaxPlayers(maxPlayers, now + MAX_PLAYERS_TTL_MS));
            } catch (RuntimeException | LinkageError error) {
                // 节点不可达/配置未生成/解析失败：不缓存结论本身，短 TTL 后重试
                maxPlayersCache.put(instance.id(), new CachedMaxPlayers(cached == null ? 0 : cached.maxPlayers(),
                        now + MAX_PLAYERS_RETRY_MS));
                return cached == null ? 0 : cached.maxPlayers();
            }
        }
        return maxPlayers;
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        maxPlayersCache.clear();
    }
}
