package online.yudream.base.plugin.minecraft.interfaces.http;

import online.yudream.base.plugin.minecraft.application.service.MinecraftServerAppService;
import online.yudream.base.plugin.minecraft.interfaces.support.JsonSupport;
import online.yudream.base.plugin.minecraft.interfaces.assembler.MinecraftServerWebAssembler;
import online.yudream.base.plugin.minecraft.interfaces.request.MinecraftPlayerEventRequest;
import online.yudream.base.plugin.minecraft.interfaces.request.MinecraftPlayerSnapshotRequest;
import online.yudream.base.plugin.minecraft.interfaces.request.MinecraftSeasonOpenRequest;
import online.yudream.base.plugin.minecraft.interfaces.request.MinecraftServerSaveRequest;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.io.IOException;
import online.yudream.base.plugin.minecraft.interfaces.request.MinecraftServerMapSaveRequest;
import online.yudream.base.plugin.minecraft.interfaces.request.MinecraftServerMapPublicAccessRequest;
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
        boolean closed = boolQuery(request, "closed", false);
        var page = closed ? appService.archivedServers(page(request), size(request)) : appService.pageServers(false, refresh, page(request), size(request));
        return PluginHttpResponse.ok(Map.of("records", page.records().stream().map(assembler::toRes).toList(), "total", page.total()));
    }

    public PluginHttpResponse adminList(PluginHttpRequest request) {
        boolean refresh = boolQuery(request, "refresh", false);
        var page = appService.pageServers(true, refresh, page(request), size(request));
        return PluginHttpResponse.ok(Map.of("records", page.records().stream().map(assembler::toRes).toList(), "total", page.total()));
    }

    public PluginHttpResponse archivedList(PluginHttpRequest request) {
        var page = appService.archivedServers(page(request), size(request));
        return PluginHttpResponse.ok(Map.of("records", page.records().stream().map(assembler::toRes).toList(), "total", page.total()));
    }

    public PluginHttpResponse archivedDetail(PluginHttpRequest request) {
        return PluginHttpResponse.ok(assembler.toRes(appService.archivedDetail(pathSegment(request.path(), 2))));
    }

    public PluginHttpResponse userDetail(PluginHttpRequest request) {
        boolean closed = boolQuery(request, "closed", false);
        return PluginHttpResponse.ok(assembler.toRes(closed ? appService.archivedDetail(pathSegment(request.path(), 1)) : appService.userDetail(pathSegment(request.path(), 1), boolQuery(request, "refresh", false))));
    }

    public PluginHttpResponse adminDetail(PluginHttpRequest request) {
        return PluginHttpResponse.ok(assembler.toRes(appService.detail(pathSegment(request.path(), 2), boolQuery(request, "refresh", false))));
    }

    public PluginHttpResponse saveMap(PluginHttpRequest request) {
        MinecraftServerMapSaveRequest body = JsonSupport.read(request.body(), MinecraftServerMapSaveRequest.class);
        return PluginHttpResponse.ok(assembler.toRes(appService.saveMap(pathSegment(request.path(), 2), body.fileId())));
    }

    public PluginHttpResponse setMapPublicAccess(PluginHttpRequest request) {
        MinecraftServerMapPublicAccessRequest body = JsonSupport.read(request.body(), MinecraftServerMapPublicAccessRequest.class);
        if (body.publicAccess() == null) throw new IllegalArgumentException("公开状态不能为空");
        return PluginHttpResponse.ok(assembler.toRes(appService.setMapPublicAccess(pathSegment(request.path(), 2), body.publicAccess())));
    }

    public PluginHttpResponse deleteMap(PluginHttpRequest request) {
        appService.deleteMap(pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    public PluginHttpResponse publicMapDownload(PluginHttpRequest request) { return mapResponse(appService.downloadMap(pathSegment(request.path(), 1), true, true)); }
    public PluginHttpResponse adminMapDownload(PluginHttpRequest request) { return mapResponse(appService.downloadMap(pathSegment(request.path(), 2), false, true)); }

    private PluginHttpResponse mapResponse(online.yudream.base.plugin.spi.system.storage.PluginStoredFile file) {
        try (var input = file.inputStream()) {
            return new PluginHttpResponse(200, Map.of("Content-Disposition", "attachment; filename=map.zip", "Cache-Control", "no-cache"), "application/zip", input.readAllBytes(), false);
        } catch (IOException e) { return PluginHttpResponse.rawJson(500, Map.of("message", "地图读取失败")); }
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

    public PluginHttpResponse playerSnapshot(PluginHttpRequest request) {
        MinecraftPlayerSnapshotRequest body = JsonSupport.read(request.body(), MinecraftPlayerSnapshotRequest.class);
        int onlinePlayers = appService.reconcilePlayerSnapshot(reportServerId(request), assembler.toCmd(body));
        return PluginHttpResponse.ok(Map.of("onlinePlayers", onlinePlayers));
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
