package online.yudream.base.plugin.minecraft.infrastructure.launcher;

import online.yudream.base.plugin.minecraft.application.dto.MinecraftEndpointStatusDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftPageDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftServerDTO;
import online.yudream.base.plugin.minecraft.application.service.MinecraftServerAppService;
import online.yudream.base.plugin.minecraft.bootstrap.MinecraftServerPlugin;
import online.yudream.base.plugin.ymcl.api.YmclBundleContribution;
import online.yudream.base.plugin.ymcl.api.YmclContributionProvider;
import online.yudream.base.plugin.ymcl.api.YmclDataContext;
import online.yudream.base.plugin.ymcl.api.YmclDataSourceDescriptor;
import online.yudream.base.plugin.ymcl.api.YmclModuleSupport;
import online.yudream.base.plugin.ymcl.api.YmclPageDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mc-server 向 YMCL 适配器（ymcl-adapter）贡献服务器列表页（YAP §3 扩展点）。
 *
 * ymcl-adapter 缺失时由 bootstrap 捕获 LinkageError 降级。
 */
public class MinecraftYmclContributionProvider implements YmclContributionProvider {

    public static final String PAGE_SERVERS = "mc.servers";
    public static final String DATA_SOURCE_SERVERS = "servers";
    public static final int DATA_SOURCE_SCHEMA_VERSION = 2;
    public static final int DATA_SOURCE_CACHE_TTL = 30;

    /** 服务器页 module 资源（YAP §6.8）：jar 内 ESM，经适配器 bundle 通道下发。 */
    private static final String MODULE_RESOURCE = "/ymcl-pages/servers-page.js";
    private static final String MODULE_BUNDLE_ID = "ymcl-mc-servers";
    private static final String MODULE_ENTRY = "servers-page.js";

    private final MinecraftServerAppService appService;

    public MinecraftYmclContributionProvider(MinecraftServerAppService appService) {
        this.appService = appService;
    }

    private static YmclModuleSupport.YmclModuleBundle moduleBundle() {
        return YmclModuleSupport.fromResource(
                MinecraftYmclContributionProvider.class,
                MODULE_RESOURCE,
                MODULE_BUNDLE_ID,
                MODULE_ENTRY,
                List.of(YmclModuleSupport.PERMISSION_DATA_FETCH, YmclModuleSupport.PERMISSION_ACTION_EXECUTE));
    }

    @Override
    public String providerCode() {
        return MinecraftServerPlugin.CODE;
    }

    @Override
    public List<YmclPageDescriptor> pages() {
        YmclModuleSupport.YmclModuleBundle module = moduleBundle();
        if (module != null) {
            // module 页面自持数据拉取；dataSource 仍须绑定——启动器首页卡片
            // 「全部」与裸数据源路由按它反查宿主页面，置空会回退到合成旧渲染页。
            // DomainPageHost 对 module 渲染器跳过信封拉取，不会重复取数。
            return List.of(new YmclPageDescriptor(
                    PAGE_SERVERS,
                    "module",
                    "服务器",
                    "i-ri:server-line",
                    providerCode() + "." + DATA_SOURCE_SERVERS,
                    10,
                    MinecraftServerPlugin.VIEW_PERMISSION,
                    Map.of(),
                    module.descriptor()
            ));
        }
        // 资源缺失时降级为内置 server-list 渲染器，页面不失联。
        return List.of(new YmclPageDescriptor(
                PAGE_SERVERS,
                "server-list",
                "服务器",
                "i-ri:server-line",
                providerCode() + "." + DATA_SOURCE_SERVERS,
                10,
                MinecraftServerPlugin.VIEW_PERMISSION,
                Map.of("showStatus", true),
                null
        ));
    }

    @Override
    public List<YmclBundleContribution> bundles() {
        YmclModuleSupport.YmclModuleBundle module = moduleBundle();
        return module == null ? List.of() : List.of(module.contribution());
    }

    @Override
    public List<YmclDataSourceDescriptor> dataSources() {
        return List.of(new YmclDataSourceDescriptor(
                DATA_SOURCE_SERVERS,
                "服务器",
                DATA_SOURCE_SCHEMA_VERSION,
                DATA_SOURCE_CACHE_TTL
        ));
    }

    @Override
    public Object serverBindings() {
        List<Map<String, Object>> servers = new ArrayList<>();
        MinecraftPageDTO<MinecraftServerDTO> result = appService.pageServers(false, true, 1, 200);
        for (MinecraftServerDTO dto : result.records()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("serverId", dto.id());
            view.put("name", dto.name());
            view.put("mcAddress", primaryAddress(dto));
            view.put("status", statusView(dto));
            view.put("endpoints", endpointViews(dto));
            var season = dto.currentSeason();
            if (season != null) {
                view.put("currentSeason", Map.of(
                        "seasonId", String.valueOf(season.id()),
                        "name", String.valueOf(season.name())));
            }
            servers.add(view);
        }
        return servers;
    }

    private static String primaryAddress(MinecraftServerDTO dto) {
        var primary = primaryEndpoint(dto);
        return primary == null ? null : primary.address();
    }

    private static String statusView(MinecraftServerDTO dto) {
        if (dto.status() == null || dto.status().status() == null) {
            return "unknown";
        }
        return "ONLINE".equalsIgnoreCase(dto.status().status()) ? "online" : "offline";
    }

    @Override
    public Object fetchData(String sourceCode, YmclDataContext context) {
        int page = Math.max(1, context.page());
        int pageSize = Math.max(1, Math.min(200, context.pageSize()));
        MinecraftPageDTO<MinecraftServerDTO> result = appService.pageServers(false, true, page, pageSize);
        List<Map<String, Object>> records = result.records().stream()
                .map(MinecraftYmclContributionProvider::toCard)
                .toList();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", DATA_SOURCE_SCHEMA_VERSION);
        envelope.put("records", records);
        envelope.put("total", result.total());
        envelope.put("actions", List.of(
                action("refresh", "刷新", "client:reload", false, Map.of()),
                action("copy-all", "复制全部地址", "client:copy", false,
                        Map.of("text", "{{items.address}}"))
        ));
        envelope.put("itemActions", List.of(
                action("join", "加入游戏", "client:launch-server", true,
                        Map.of("address", "{{item.address}}")),
                action("copy-address", "复制地址", "client:copy", false,
                        Map.of("text", "{{item.address}}"))
        ));
        envelope.put("cacheTtl", DATA_SOURCE_CACHE_TTL);
        return envelope;
    }

    private static MinecraftServerDTO.EndpointDTO primaryEndpoint(MinecraftServerDTO dto) {
        if (dto.endpoints() == null || dto.endpoints().isEmpty()) {
            return null;
        }
        return dto.endpoints().stream()
                .filter(MinecraftServerDTO.EndpointDTO::primaryLine)
                .findFirst()
                .orElse(dto.endpoints().getFirst());
    }

    private static Map<String, Object> toCard(MinecraftServerDTO dto) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", dto.id());
        card.put("name", dto.name());
        card.put("description", dto.descriptionMarkdown());
        card.put("enabled", dto.enabled());
        card.put("sort", dto.sort());
        var primary = primaryEndpoint(dto);
        if (primary != null) {
            card.put("address", primary.address());
            card.put("edition", primary.edition());
        }
        card.put("endpoints", endpointViews(dto));
        if (dto.status() != null) {
            card.put("online", "ONLINE".equalsIgnoreCase(dto.status().status()));
            card.put("onlinePlayers", dto.status().onlinePlayers());
            card.put("maxPlayers", dto.status().maxPlayers());
            var endpointStatuses = dto.status().endpoints() == null
                    ? List.<MinecraftEndpointStatusDTO>of() : dto.status().endpoints();
            String primaryId = primary == null ? null : primary.id();
            endpointStatuses.stream()
                    .filter(item -> primaryId != null && primaryId.equals(item.endpointId()))
                    .findFirst()
                    .or(() -> endpointStatuses.stream()
                            .filter(item -> "ONLINE".equalsIgnoreCase(item.status()))
                            .findFirst())
                    .ifPresent(item -> {
                        card.put("onlinePlayers", item.onlinePlayers());
                        card.put("maxPlayers", item.maxPlayers());
                        card.put("versionName", item.versionName());
                        card.put("ping", item.ping());
                        card.put("motd", item.motd());
                        card.put("favicon", item.favicon());
                        card.put("players", item.players() == null ? List.of() : item.players().stream()
                                .map(player -> {
                                    Map<String, Object> view = new LinkedHashMap<String, Object>();
                                    view.put("id", player.id());
                                    view.put("name", player.name());
                                    return view;
                                })
                                .toList());
                    });
        }
        var season = dto.currentSeason();
        if (season != null) {
            Map<String, Object> seasonView = new LinkedHashMap<>();
            seasonView.put("id", season.id());
            seasonView.put("name", season.name());
            seasonView.put("startedAt", season.startedAt() == null ? null : String.valueOf(season.startedAt()));
            seasonView.put("endedAt", season.endedAt() == null ? null : String.valueOf(season.endedAt()));
            if (season.modpackBinding() != null) {
                seasonView.put("modpackBinding", season.modpackBinding());
            }
            card.put("currentSeason", seasonView);
        }
        return card;
    }

    private static List<Map<String, Object>> endpointViews(MinecraftServerDTO dto) {
        if (dto.endpoints() == null || dto.endpoints().isEmpty()) {
            return List.of();
        }
        return dto.endpoints().stream()
                .filter(endpoint -> endpoint.enabled())
                .sorted((left, right) -> {
                    if (left.primaryLine() != right.primaryLine()) {
                        return left.primaryLine() ? -1 : 1;
                    }
                    return Integer.compare(left.sort(), right.sort());
                })
                .map(endpoint -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("address", endpoint.address());
                    view.put("primary", endpoint.primaryLine());
                    view.put("edition", String.valueOf(endpoint.edition()));
                    view.put("name", endpoint.name());
                    return view;
                })
                .toList();
    }

    private static Map<String, Object> action(
            String code, String title, String kind, boolean primary, Map<String, Object> params) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", code);
        view.put("title", title);
        view.put("kind", kind);
        view.put("primary", primary);
        view.put("params", params);
        return view;
    }
}
