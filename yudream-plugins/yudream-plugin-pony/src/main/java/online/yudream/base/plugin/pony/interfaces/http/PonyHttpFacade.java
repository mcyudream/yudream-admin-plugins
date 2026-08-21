package online.yudream.base.plugin.pony.interfaces.http;

import online.yudream.base.plugin.pony.application.PonyAppService;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.List;
import java.util.Map;

public class PonyHttpFacade {

    private final PonyAppService appService;

    public PonyHttpFacade(PonyAppService appService) {
        this.appService = appService;
    }

    // ---------------------------------------------------------------- 用户端

    public PluginHttpResponse myStats(PluginHttpRequest request) {
        var view = appService.myStatsView(currentUserId(request));
        return PluginHttpResponse.ok(view == null ? Map.of("empty", true) : view);
    }

    // ---------------------------------------------------------------- 管理端

    public PluginHttpResponse overview() {
        return PluginHttpResponse.ok(appService.overview());
    }

    public PluginHttpResponse games(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.searchGames(stringQuery(request, "status"), page(request), size(request)));
    }

    public PluginHttpResponse players(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.searchPlayers(page(request), size(request)));
    }

    // ---------------------------------------------------------------- 边界解析

    private String currentUserId(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return String.valueOf(request.principal().userId());
    }

    private int page(PluginHttpRequest request) {
        return intQuery(request, "page", 1);
    }

    private int size(PluginHttpRequest request) {
        return Math.min(Math.max(intQuery(request, "size", 10), 1), 100);
    }

    private int intQuery(PluginHttpRequest request, String key, int fallback) {
        String value = stringQuery(request, key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(Integer.parseInt(value), 1);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String stringQuery(PluginHttpRequest request, String key) {
        Map<String, List<String>> query = request.query();
        if (query == null) {
            return null;
        }
        List<String> values = query.get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.getFirst();
        return value == null || value.isBlank() ? null : value;
    }
}
