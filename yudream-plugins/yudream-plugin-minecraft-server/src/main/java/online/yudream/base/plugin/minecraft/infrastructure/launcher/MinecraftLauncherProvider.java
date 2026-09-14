package online.yudream.base.plugin.minecraft.infrastructure.launcher;

import online.yudream.base.plugin.launcher.api.LauncherAction;
import online.yudream.base.plugin.launcher.api.LauncherContextView;
import online.yudream.base.plugin.launcher.api.LauncherDataEnvelope;
import online.yudream.base.plugin.launcher.api.LauncherDataSource;
import online.yudream.base.plugin.launcher.api.LauncherPage;
import online.yudream.base.plugin.launcher.api.LauncherProvider;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftPageDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftServerDTO;
import online.yudream.base.plugin.minecraft.application.service.MinecraftServerAppService;
import online.yudream.base.plugin.minecraft.bootstrap.MinecraftServerPlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mc-server 向启动器贡献 server-list 页面。launcher-adapter 缺失时由 bootstrap 捕获 LinkageError 降级。
 */
public class MinecraftLauncherProvider implements LauncherProvider {

    public static final String PAGE_SERVERS = "mc.servers";
    public static final String DATA_SOURCE_SERVERS = "mc.servers.list";
    public static final int DATA_SOURCE_SCHEMA_VERSION = 2;

    private final MinecraftServerAppService appService;

    public MinecraftLauncherProvider(MinecraftServerAppService appService) {
        this.appService = appService;
    }

    @Override
    public String providerCode() {
        return "minecraft-server";
    }

    @Override
    public List<LauncherPage> pages() {
        return List.of(new LauncherPage(
                PAGE_SERVERS,
                "Minecraft 服务器",
                "i-ri:server-line",
                10,
                "server-list",
                "/domain/servers",
                DATA_SOURCE_SERVERS,
                null,
                providerCode(),
                Map.of("showStatus", true, "playerClickAction", "copy"),
                MinecraftServerPlugin.VIEW_PERMISSION
        ));
    }

    @Override
    public List<LauncherDataSource> dataSources() {
        return List.of(new LauncherDataSource(
                DATA_SOURCE_SERVERS,
                "服务器列表",
                DATA_SOURCE_SCHEMA_VERSION,
                PAGE_SERVERS
        ));
    }

    @Override
    public Object fetchData(String dataSourceCode, LauncherContextView context) {
        if (!DATA_SOURCE_SERVERS.equals(dataSourceCode)) {
            return LauncherDataEnvelope.paged(List.of(), 0);
        }
        int page = context == null ? 1 : Math.max(1, context.page());
        int pageSize = context == null ? 20 : Math.max(1, Math.min(200, context.pageSize()));
        MinecraftPageDTO<MinecraftServerDTO> result = appService.pageServers(false, true, page, pageSize);
        List<Map<String, Object>> records = result.records().stream().map(MinecraftLauncherProvider::toCard).toList();
        return new LauncherDataEnvelope(
                records,
                result.total(),
                List.of(
                        new LauncherAction("refresh", "刷新", "i-ri:refresh-line",
                                "secondary", "client:reload", Map.of(), ""),
                        new LauncherAction("copy-all", "复制全部地址", "i-ri:file-copy-line",
                                "secondary", "client:copy", Map.of("text", "{items.address}"), "")
                ),
                List.of(
                        new LauncherAction("join", "加入游戏", "i-ri:play-line",
                                "primary", "client:launch-server", Map.of("address", "{item.address}"), ""),
                        new LauncherAction("copy-address", "复制地址", "i-ri:file-copy-line",
                                "secondary", "client:copy", Map.of("text", "{item.address}"), "")
                ));
    }

    static Map<String, Object> toCard(MinecraftServerDTO dto) {
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
}
