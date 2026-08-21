package online.yudream.base.plugin.mcguess.interfaces.http;

import online.yudream.base.plugin.mcguess.application.McguessStatsService;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.List;
import java.util.Map;

public class McguessHttpFacade {

    private final McguessStatsService statsService;

    public McguessHttpFacade(McguessStatsService statsService) {
        this.statsService = statsService;
    }

    // ---------------------------------------------------------------- 用户端

    public PluginHttpResponse myStats(PluginHttpRequest request) {
        var view = statsService.myStatsView(currentUserId(request));
        return PluginHttpResponse.ok(view == null ? Map.of("empty", true) : view);
    }

    // ---------------------------------------------------------------- 管理端

    public PluginHttpResponse overview() {
        return PluginHttpResponse.ok(statsService.overview());
    }

    public PluginHttpResponse games(PluginHttpRequest request) {
        return PluginHttpResponse.ok(statsService.searchGames(stringQuery(request, "mode"), stringQuery(request, "status"),
                page(request), size(request)));
    }

    public PluginHttpResponse players(PluginHttpRequest request) {
        return PluginHttpResponse.ok(statsService.searchPlayers(page(request), size(request)));
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
