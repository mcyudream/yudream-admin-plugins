package online.yudream.base.plugin.playtimepoints.interfaces.http;

import online.yudream.base.plugin.playtimepoints.application.assembler.PointsSettlementAssembler;
import online.yudream.base.plugin.playtimepoints.application.dto.CheckInRewardPage;
import online.yudream.base.plugin.playtimepoints.application.dto.CheckInScanResult;
import online.yudream.base.plugin.playtimepoints.application.dto.ScanResult;
import online.yudream.base.plugin.playtimepoints.application.service.CheckInRewardService;
import online.yudream.base.plugin.playtimepoints.application.service.PlaytimePointsAppService;
import online.yudream.base.plugin.playtimepoints.domain.valobj.PlaytimePointsSettings;
import online.yudream.base.plugin.playtimepoints.interfaces.assembler.PlaytimePointsSettingsAssembler;
import online.yudream.base.plugin.playtimepoints.interfaces.request.SaveSettingsRequest;
import online.yudream.base.plugin.playtimepoints.interfaces.support.JsonSupport;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP 边界：只做参数解析与响应包装，业务在应用服务。 */
public class PlaytimePointsHttpFacade {

    /** 用户端「最近打卡积分」展示条数。 */
    private static final int RECENT_CHECK_IN_LIMIT = 10;

    private final PlaytimePointsAppService appService;
    private final CheckInRewardService checkInRewards;

    public PlaytimePointsHttpFacade(PlaytimePointsAppService appService, CheckInRewardService checkInRewards) {
        this.appService = appService;
        this.checkInRewards = checkInRewards;
    }

    /* ---------- 管理端 ---------- */

    /** 设置页数据源：依赖可用性（含 project-progress）、服务器/子服拓扑、货币选项与项目列表。 */
    public PluginHttpResponse adminOptions(PluginHttpRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>(appService.options());
        Map<String, Object> dependencies = new LinkedHashMap<>();
        if (payload.get("dependencies") instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> dependencies.put(String.valueOf(key), value));
        }
        dependencies.put("projectProgress", checkInRewards.available());
        payload.put("dependencies", dependencies);
        payload.put("projects", checkInRewards.projectOptions());
        Map<String, Object> checkInStatus = new LinkedHashMap<>();
        checkInStatus.put("enabled", checkInRewards.enabled());
        checkInStatus.put("realtimeRegistered", checkInRewards.realtimeRegistered());
        checkInStatus.put("providerAvailable", checkInRewards.available());
        payload.put("checkInRewardsStatus", checkInStatus);
        return PluginHttpResponse.ok(payload);
    }

    public PluginHttpResponse adminSettings(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.settings());
    }

    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        SaveSettingsRequest body = JsonSupport.read(request.body(), SaveSettingsRequest.class);
        PlaytimePointsSettings saved = appService.saveSettings(PlaytimePointsSettingsAssembler.toSettings(body));
        // 实时开关可能是本次刚打开的：保存后按最新设置尝试注册（幂等；关闭/provider 不可用时静默跳过）
        try {
            checkInRewards.registerRealtimeListener();
        } catch (RuntimeException | LinkageError ignored) {
            // 注册失败不影响设置保存结果，拉取路径仍然可用
        }
        return PluginHttpResponse.ok(saved);
    }

    public PluginHttpResponse adminSettlements(PluginHttpRequest request) {
        var page = appService.adminSettlements(query(request, "serverId"), query(request, "keyword"),
                page(request), size(request));
        return PluginHttpResponse.ok(Map.of("records", page.records(), "total", page.total()));
    }

    /** 打卡积分发放流水（管理端，跨用户）：可按项目与用户筛选。 */
    public PluginHttpResponse adminCheckInRewards(PluginHttpRequest request) {
        CheckInRewardPage page = checkInRewards.adminPage(query(request, "projectId"), query(request, "userId"),
                page(request), size(request));
        return PluginHttpResponse.ok(Map.of("records", page.records(), "total", page.total()));
    }

    /** 手动触发：先做一轮在线时长结算，再做一轮打卡积分拉取。 */
    public PluginHttpResponse adminRun(PluginHttpRequest request) {
        ScanResult settlement = appService.scan();
        CheckInScanResult checkIn = checkInRewards.scan();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("credited", settlement.credited());
        payload.put("sessions", settlement.sessions());
        payload.put("message", settlement.message());
        payload.put("checkInRewards", checkIn);
        return PluginHttpResponse.ok(payload);
    }

    public PluginHttpResponse adminLastScan(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.lastScan());
    }

    /* ---------- 用户端 ---------- */

    /** 我的积分汇总：时长积分 + 打卡积分合计与最近记录（归属只取登录主体）。 */
    public PluginHttpResponse mySummary(PluginHttpRequest request) {
        String userId = requireUserId(request);
        Map<String, Object> payload = new LinkedHashMap<>(appService.mySummary(userId));
        payload.put("checkInRewards", checkInRewards.mySummary(userId, RECENT_CHECK_IN_LIMIT));
        return PluginHttpResponse.ok(payload);
    }

    public PluginHttpResponse mySettlements(PluginHttpRequest request) {
        var page = appService.mySettlements(requireUserId(request), page(request), size(request));
        return PluginHttpResponse.ok(Map.of("records", page.records(), "total", page.total()));
    }

    /* ---------- 解析辅助 ---------- */

    private static String requireUserId(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            throw new IllegalArgumentException("未登录或身份缺失");
        }
        return String.valueOf(request.principal().userId());
    }

    private static String query(PluginHttpRequest request, String name) {
        List<String> values = request.query().get(name);
        return values == null || values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()
                ? null
                : values.getFirst().trim();
    }

    private static int page(PluginHttpRequest request) {
        String value = query(request, "page");
        try {
            return value == null ? 1 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int size(PluginHttpRequest request) {
        String value = query(request, "size");
        try {
            return value == null ? 10 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 10;
        }
    }
}
