package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.minecraft.api.PluginMinecraftServer;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * minecraft-server 联动端口（设计 §5.7）：
 * - 子服选项代理：管理端禁止手输 ID，选项来自提供方查询（含当前周目供 TERM 注入预览）；
 * - 绑定校验：绑定的子服必须存在且启用，提供方缺失时禁止新建绑定（既有绑定不受影响）；
 * - 注入联动：绑定实例创建/改配时把 MCSERVER_ID（子服）与 MCSERVER_TERM（当前周目）
 *   合并进容器环境变量——时长记录插件读取后按子服维度记账；
 * - 运行时状态回传：提供方 api 包当前只有读取契约，无回写方法——本服务不伪造回传，
 *   待提供方新增回写契约后接入（见验收文档 M5 行）。
 * 提供方缺失（softdepend 未装/未启用，或提供方在宿主的加载顺序晚于本插件）时：
 * 选项 available=false、绑定 UI 降级隐藏，面板全部功能独立可用。
 */
public class MinecraftLinkService {

    public static final String PROVIDER_CODE = "minecraft-server";

    private final Supplier<Optional<PluginMinecraftService>> provider;

    public MinecraftLinkService(Supplier<Optional<PluginMinecraftService>> provider) {
        this.provider = provider;
    }

    /**
     * 提供方查找统一入口。supplier 里持有提供方 api 类的字面量，提供方未安装/未启用、
     * 或其宿主加载顺序晚于本插件（软依赖 ClassLoader 在消费者加载时一次性捕获，后加载不补）
     * 时，字面量解析会抛 LinkageError——必须在此收敛为 empty，与 AuthlibLinkService 同一降级语义。
     */
    private Optional<PluginMinecraftService> provider() {
        try {
            return provider.get();
        } catch (LinkageError unavailable) {
            return Optional.empty();
        }
    }

    public boolean available() {
        return provider().isPresent();
    }

    /** 子服选项（禁止手输）：{available, servers:[{id,name,enabled,currentSeasonId,subServers[]}]}。 */
    public Map<String, Object> options() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", available());
        result.put("servers", List.of());
        return provider().map(service -> {
            List<Map<String, Object>> servers = service.minecraftServers(true).stream()
                    .map(server -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", server.id());
                        item.put("name", server.name());
                        item.put("enabled", server.enabled());
                        item.put("currentSeasonId", server.currentSeasonId());
                        item.put("currentSeasonName", server.currentSeasonName());
                        item.put("subServers", server.subServers().stream()
                                .map(sub -> Map.of("name", sub.name(), "online", sub.online(),
                                        "sensor", sub.sensor(), "defaultServer", sub.defaultServer()))
                                .toList());
                        return item;
                    })
                    .toList();
            result.put("servers", servers);
            return result;
        }).orElse(result);
    }

    public Optional<PluginMinecraftServer> server(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return Optional.empty();
        }
        return provider().flatMap(service -> service.minecraftServer(serverId));
    }

    /** 绑定合法性：空 = 未绑定（合法）；返回错误描述或 null。 */
    public String bindingProblem(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return null;
        }
        if (!available()) {
            return "minecraft-server 插件不可用，无法校验绑定目标";
        }
        Optional<PluginMinecraftServer> server = server(serverId);
        if (server.isEmpty()) {
            return "绑定的子服不存在（minecraft-server 中找不到 " + serverId + "）";
        }
        if (!server.get().enabled()) {
            return "绑定的子服已停用";
        }
        return null;
    }

    /**
     * 注入联动：绑定实例的容器 env——MCSERVER_ID = 绑定子服，
     * MCSERVER_TERM = 该子服当前周目（解析不到周目时只下发 ID）。
     */
    public Map<String, String> injectEnv(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return Map.of();
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put("MCSERVER_ID", serverId);
        server(serverId).map(PluginMinecraftServer::currentSeasonId)
                .ifPresent(season -> env.put("MCSERVER_TERM", season));
        return env;
    }

    // ---------- 状态回传（§5.7） ----------

    private long writebackFailures;
    private long writebackSuccesses;

    /**
     * 实例状态变化回写提供方（公开服务器列表实时展示在线/离线）。
     * 失败（提供方缺失/子服不存在/异常/提供方版本过旧无回写契约）只计数，
     * 不影响实例本身——降级语义。LinkageError（如 NoSuchMethodError，旧版
     * 提供方缺少新增契约方法）必须在此吞掉，否则会穿透事件监听链杀掉连接线程。
     */
    public void writebackState(online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance instance) {
        if (instance == null || instance.mcServerId() == null || instance.mcServerId().isBlank()) {
            return;
        }
        provider().ifPresentOrElse(service -> {
            try {
                service.notifyPanelInstanceState(instance.mcServerId(), instance.id(),
                        String.valueOf(instance.state()), System.currentTimeMillis());
                writebackSuccesses++;
            } catch (RuntimeException | LinkageError error) {
                writebackFailures++;
            }
        }, () -> writebackFailures++);
    }

    public long writebackFailureCount() {
        return writebackFailures;
    }

    public long writebackSuccessCount() {
        return writebackSuccesses;
    }
}
