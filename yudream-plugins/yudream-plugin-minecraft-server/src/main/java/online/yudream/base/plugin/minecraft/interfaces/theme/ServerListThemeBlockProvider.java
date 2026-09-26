package online.yudream.base.plugin.minecraft.interfaces.theme;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftServerDTO;
import online.yudream.base.plugin.minecraft.application.service.MinecraftServerAppService;
import online.yudream.base.plugin.minecraft.infrastructure.service.MinecraftStatusService;
import online.yudream.base.plugin.minecraft.interfaces.support.BriefText;
import online.yudream.base.plugin.spi.theme.PluginThemeBlockContext;
import online.yudream.base.plugin.spi.theme.PluginThemeBlockProvider;

/**
 * 主题块「服务器列表」：向公开站主题模板贡献已启用服务器的展示视图。
 * 状态一律取自调度器维护的快照缓存（{@code listServers(false, false)}），
 * 严格遵守块提供者「不做实时外部调用」的约定；仅暴露名称/地址/在线状态等公开字段。
 */
public final class ServerListThemeBlockProvider implements PluginThemeBlockProvider {

    private final MinecraftServerAppService appService;

    public ServerListThemeBlockProvider(MinecraftServerAppService appService) {
        this.appService = appService;
    }

    @Override
    public String code() {
        return "server-list";
    }

    @Override
    public String name() {
        return "服务器列表";
    }

    @Override
    public Object data(PluginThemeBlockContext ctx) {
        List<MinecraftServerDTO> servers = appService.listServers(false, false);
        int limit = ctx.limit() <= 0 ? servers.size() : Math.min(ctx.limit(), servers.size());
        List<Map<String, Object>> views = servers.stream().limit(limit).map(this::view).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("servers", views);
        data.put("count", views.size());
        return data;
    }

    private Map<String, Object> view(MinecraftServerDTO server) {
        var status = server.status();
        var primary = server.endpoints().stream().filter(MinecraftServerDTO.EndpointDTO::primaryLine).findFirst()
                .orElse(server.endpoints().isEmpty() ? null : server.endpoints().getFirst());
        var endpointStatus = status == null ? null : status.endpoints().stream()
                .filter(item -> primary != null && item.endpointId().equals(primary.id())).findFirst()
                .orElse(status.endpoints().isEmpty() ? null : status.endpoints().getFirst());
        boolean online = status != null && "ONLINE".equalsIgnoreCase(status.status());
        int onlinePlayers = status == null ? 0 : status.onlinePlayers();
        int maxPlayers = status == null ? 0 : status.maxPlayers();
        // mcpanel 回传（§5.7）：ping 快照缺失时用新鲜的面板实例状态兜底展示，
        // 并始终附带 panel 明细供模板按需渲染；已有 ping 快照的语义不变。
        Map<String, Object> panel = appService.panelView(server.id());
        boolean panelOnline = Boolean.TRUE.equals(panel.get("online"));
        boolean derivedOnline = online || (status == null && panelOnline);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", server.name());
        view.put("icon", endpointStatus == null || endpointStatus.favicon() == null ? "" : endpointStatus.favicon());
        view.put("description", BriefText.ofMarkdown(server.descriptionMarkdown()));
        view.put("address", primary == null ? "" : primary.address());
        view.put("online", derivedOnline);
        view.put("statusText", derivedOnline ? "在线" : "离线");
        view.put("motd", endpointStatus == null || endpointStatus.motd() == null || endpointStatus.motd().isBlank()
                ? "" : MinecraftStatusService.plainMotd(endpointStatus.motd()));
        view.put("playersText", maxPlayers > 0 ? onlinePlayers + "/" + maxPlayers : String.valueOf(onlinePlayers));
        view.put("latencyText", endpointStatus == null || endpointStatus.ping() == null
                ? "" : endpointStatus.ping() + " ms");
        view.put("panel", panel.isEmpty() ? null : panel);
        return view;
    }
}
