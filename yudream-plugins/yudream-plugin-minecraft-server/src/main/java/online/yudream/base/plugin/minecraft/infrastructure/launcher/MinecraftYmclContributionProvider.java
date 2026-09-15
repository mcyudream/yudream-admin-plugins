package online.yudream.base.plugin.minecraft.infrastructure.launcher;

import online.yudream.base.plugin.minecraft.application.dto.MinecraftPageDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftServerDTO;
import online.yudream.base.plugin.minecraft.application.service.MinecraftServerAppService;
import online.yudream.base.plugin.minecraft.bootstrap.MinecraftServerPlugin;
import online.yudream.base.plugin.ymcl.api.YmclContributionProvider;
import online.yudream.base.plugin.ymcl.api.YmclDataContext;
import online.yudream.base.plugin.ymcl.api.YmclDataSourceDescriptor;
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

    private final MinecraftServerAppService appService;

    public MinecraftYmclContributionProvider(MinecraftServerAppService appService) {
        this.appService = appService;
    }

    @Override
    public String providerCode() {
        return MinecraftServerPlugin.CODE;
    }

    @Override
    public List<YmclPageDescriptor> pages() {
        return List.of(new YmclPageDescriptor(
                PAGE_SERVERS,
                "server-list",
                "服务器",
                providerCode() + "." + DATA_SOURCE_SERVERS,
                10,
                MinecraftServerPlugin.VIEW_PERMISSION,
                Map.of("showStatus", true),
                null
        ));
    }

    @Override
    public List<YmclDataSourceDescriptor> dataSources() {
        return List.of(new YmclDataSourceDescriptor(
                DATA_SOURCE_SERVERS,
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
            view.put("mcAddress", null);
            view.put("status", "unknown");
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
                        Map.of("text", "{items.address}"))
        ));
        envelope.put("itemActions", List.of(
                action("join", "加入游戏", "client:launch-server", true,
                        Map.of("address", "{item.address}")),
                action("copy-address", "复制地址", "client:copy", false,
                        Map.of("text", "{item.address}"))
        ));
        envelope.put("cacheTtl", DATA_SOURCE_CACHE_TTL);
        return envelope;
    }

    private static Map<String, Object> toCard(MinecraftServerDTO dto) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", dto.id());
        card.put("name", dto.name());
        card.put("description", dto.descriptionMarkdown());
        card.put("enabled", dto.enabled());
        card.put("sort", dto.sort());
        var primary = dto.endpoints() == null ? null : dto.endpoints().stream()
                .filter(MinecraftServerDTO.EndpointDTO::primaryLine)
                .findFirst()
                .orElseGet(() -> dto.endpoints().isEmpty() ? null : dto.endpoints().getFirst());
        if (primary != null) {
            card.put("address", primary.host() + (primary.port() == 25565 ? "" : ":" + primary.port()));
            card.put("edition", primary.edition());
        }
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
