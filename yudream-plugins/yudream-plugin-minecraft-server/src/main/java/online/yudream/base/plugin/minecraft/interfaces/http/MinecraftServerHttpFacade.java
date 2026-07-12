package online.yudream.base.plugin.minecraft.interfaces.http;

import online.yudream.base.plugin.minecraft.application.service.MinecraftServerAppService;
import online.yudream.base.plugin.minecraft.interfaces.support.JsonSupport;
import online.yudream.base.plugin.minecraft.interfaces.assembler.MinecraftServerWebAssembler;
import online.yudream.base.plugin.minecraft.interfaces.request.MinecraftPlayerEventRequest;
import online.yudream.base.plugin.minecraft.interfaces.request.MinecraftSeasonOpenRequest;
import online.yudream.base.plugin.minecraft.interfaces.request.MinecraftServerSaveRequest;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class MinecraftServerHttpFacade {

    private final MinecraftServerAppService appService;
    private final MinecraftServerWebAssembler assembler = new MinecraftServerWebAssembler();

    public MinecraftServerHttpFacade(MinecraftServerAppService appService) {
        this.appService = appService;
    }

    public PluginHttpResponse userList(PluginHttpRequest request) {
        boolean refresh = boolQuery(request, "refresh", false);
        var page = appService.pageServers(false, refresh, page(request), size(request));
        return PluginHttpResponse.ok(Map.of("records", page.records().stream().map(assembler::toRes).toList(), "total", page.total()));
    }

    public PluginHttpResponse adminList(PluginHttpRequest request) {
        boolean refresh = boolQuery(request, "refresh", false);
        var page = appService.pageServers(true, refresh, page(request), size(request));
        return PluginHttpResponse.ok(Map.of("records", page.records().stream().map(assembler::toRes).toList(), "total", page.total()));
    }

    public PluginHttpResponse userDetail(PluginHttpRequest request) {
        return PluginHttpResponse.ok(assembler.toRes(appService.userDetail(pathSegment(request.path(), 1), boolQuery(request, "refresh", false))));
    }

    public PluginHttpResponse adminDetail(PluginHttpRequest request) {
        return PluginHttpResponse.ok(assembler.toRes(appService.detail(pathSegment(request.path(), 2), boolQuery(request, "refresh", false))));
    }

    public PluginHttpResponse save(PluginHttpRequest request) {
        MinecraftServerSaveRequest body = JsonSupport.read(request.body(), MinecraftServerSaveRequest.class);
        return PluginHttpResponse.ok(assembler.toRes(appService.saveServer(assembler.toCmd(body))));
    }

    public PluginHttpResponse delete(PluginHttpRequest request) {
        appService.deleteServer(pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    public PluginHttpResponse refreshStatus(PluginHttpRequest request) {
        return PluginHttpResponse.ok(assembler.toRes(appService.detail(pathSegment(request.path(), 2), true)));
    }

    public PluginHttpResponse statusHistory(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.statusHistory(pathSegment(request.path(), 1), longQuery(request, "since"), intQuery(request, "limit", 0)).stream()
                .map(assembler::toRes)
                .toList());
    }

    public PluginHttpResponse economyStatus() {
        return PluginHttpResponse.ok(Map.of("walletEnabled", appService.walletEnabled()));
    }

    public PluginHttpResponse previewOpenSeason(PluginHttpRequest request) {
        MinecraftSeasonOpenRequest body = JsonSupport.read(request.body(), MinecraftSeasonOpenRequest.class);
        return PluginHttpResponse.ok(assembler.toRes(appService.previewOpenSeason(pathSegment(request.path(), 2), assembler.toCmd(body), userId(request))));
    }

    public PluginHttpResponse openSeason(PluginHttpRequest request) {
        MinecraftSeasonOpenRequest body = JsonSupport.read(request.body(), MinecraftSeasonOpenRequest.class);
        return PluginHttpResponse.ok(assembler.toRes(appService.openSeason(pathSegment(request.path(), 2), assembler.toCmd(body), userId(request))));
    }

    public PluginHttpResponse rollbackSeason(PluginHttpRequest request) {
        return PluginHttpResponse.ok(assembler.toRes(appService.rollbackSeasonOperation(pathSegment(request.path(), 2), userId(request))));
    }

    public PluginHttpResponse operations(PluginHttpRequest request) {
        var page = appService.operations(pathSegment(request.path(), 2), page(request), size(request));
        return PluginHttpResponse.ok(Map.of("records", page.records().stream().map(assembler::toRes).toList(), "total", page.total()));
    }

    public PluginHttpResponse myRecords(PluginHttpRequest request) {
        var page = appService.userRecords(pathSegment(request.path(), 2), userId(request), page(request), size(request));
        return PluginHttpResponse.ok(Map.of("records", page.records().stream().map(assembler::toRes).toList(), "total", page.total()));
    }

    public PluginHttpResponse playerActivities(PluginHttpRequest request) {
        var page = appService.playerActivities(pathSegment(request.path(), 2), page(request), size(request));
        return PluginHttpResponse.ok(Map.of("records", page.records().stream().map(assembler::toRes).toList(), "total", page.total()));
    }

    public PluginHttpResponse playerJoin(PluginHttpRequest request) {
        MinecraftPlayerEventRequest body = JsonSupport.read(request.body(), MinecraftPlayerEventRequest.class);
        return PluginHttpResponse.ok(assembler.toRes(appService.recordJoin(reportServerId(request), assembler.toCmd(body))));
    }

    public PluginHttpResponse playerQuit(PluginHttpRequest request) {
        MinecraftPlayerEventRequest body = JsonSupport.read(request.body(), MinecraftPlayerEventRequest.class);
        return PluginHttpResponse.ok(assembler.toRes(appService.recordQuit(reportServerId(request), assembler.toCmd(body))));
    }

    public PluginHttpResponse playerAfkStart(PluginHttpRequest request) {
        MinecraftPlayerEventRequest body = JsonSupport.read(request.body(), MinecraftPlayerEventRequest.class);
        return PluginHttpResponse.ok(assembler.toRes(appService.recordAfkStart(reportServerId(request), assembler.toCmd(body))));
    }

    public PluginHttpResponse playerAfkEnd(PluginHttpRequest request) {
        MinecraftPlayerEventRequest body = JsonSupport.read(request.body(), MinecraftPlayerEventRequest.class);
        return PluginHttpResponse.ok(assembler.toRes(appService.recordAfkEnd(reportServerId(request), assembler.toCmd(body))));
    }

    private String userId(PluginHttpRequest request) {
        Long userId = request.principal().userId();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return String.valueOf(userId);
    }

    private boolean boolQuery(PluginHttpRequest request, String key, boolean defaultValue) {
        java.util.List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? defaultValue : Boolean.parseBoolean(values.get(0));
    }

    private int page(PluginHttpRequest request) {
        return intQuery(request, "page", 1);
    }

    private int size(PluginHttpRequest request) {
        return Math.min(intQuery(request, "size", 20), 100);
    }

    private int intQuery(PluginHttpRequest request, String key, int defaultValue) {
        java.util.List<String> values = request.query().get(key);
        return values == null || values.isEmpty() || values.get(0).isBlank() ? defaultValue : Integer.parseInt(values.get(0));
    }

    private Long longQuery(PluginHttpRequest request, String key) {
        java.util.List<String> values = request.query().get(key);
        return values == null || values.isEmpty() || values.get(0).isBlank() ? null : Long.parseLong(values.get(0));
    }

    private String pathSegment(String path, int index) {
        String[] segments = trim(path).split("/");
        return index >= 0 && index < segments.length ? decode(segments[index]) : null;
    }

    private String reportServerId(PluginHttpRequest request) {
        String[] segments = trim(request.path()).split("/");
        for (int i = 0; i + 1 < segments.length; i++) {
            if ("servers".equals(segments[i])) {
                return decode(segments[i + 1]);
            }
        }
        throw new IllegalArgumentException("上报路径缺少服务器 ID");
    }

    private String trim(String path) {
        String value = path == null ? "" : path.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private String decode(String value) {
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
