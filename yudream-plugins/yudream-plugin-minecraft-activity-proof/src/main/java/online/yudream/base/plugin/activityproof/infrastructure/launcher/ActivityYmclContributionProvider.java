package online.yudream.base.plugin.activityproof.infrastructure.launcher;

import online.yudream.base.plugin.activityproof.application.dto.ActivityProofPageDTO;
import online.yudream.base.plugin.activityproof.application.dto.UserActivityDTO;
import online.yudream.base.plugin.activityproof.application.service.ActivityProofAppService;
import online.yudream.base.plugin.activityproof.bootstrap.MinecraftActivityProofPlugin;
import online.yudream.base.plugin.ymcl.api.YmclBundleContribution;
import online.yudream.base.plugin.ymcl.api.YmclContributionProvider;
import online.yudream.base.plugin.ymcl.api.YmclDataContext;
import online.yudream.base.plugin.ymcl.api.YmclDataSourceDescriptor;
import online.yudream.base.plugin.ymcl.api.YmclModuleSupport;
import online.yudream.base.plugin.ymcl.api.YmclPageDescriptor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * activity-proof 向 YMCL 适配器（ymcl-adapter）贡献活动卡片页（YAP §3 扩展点）。
 *
 * 页面走 card-grid 渲染器；报名/取消通过 server 动作（kind
 * {@code server:{providerCode}:{actionCode}}，参数模板 {{item.x}}）回传
 * executeAction 执行。ymcl-adapter 缺失时由 bootstrap 捕获 LinkageError 降级。
 */
public class ActivityYmclContributionProvider implements YmclContributionProvider {

    public static final String PAGE_SQUARE = "activity.square";
    public static final String DATA_SOURCE_ACTIVITIES = "activities";
    public static final int DATA_SOURCE_SCHEMA_VERSION = 1;
    public static final int DATA_SOURCE_CACHE_TTL = 60;
    private static final int MAX_PAGE_SIZE = 50;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 活动页 module 资源（YAP §6.8）：jar 内 ESM，经适配器 bundle 通道下发。 */
    private static final String MODULE_RESOURCE = "/ymcl-pages/activities-page.js";
    private static final String MODULE_BUNDLE_ID = "ymcl-activity-square";
    private static final String MODULE_ENTRY = "activities-page.js";

    private final ActivityProofAppService appService;

    public ActivityYmclContributionProvider(ActivityProofAppService appService) {
        this.appService = appService;
    }

    private static YmclModuleSupport.YmclModuleBundle moduleBundle() {
        return YmclModuleSupport.fromResource(
                ActivityYmclContributionProvider.class,
                MODULE_RESOURCE,
                MODULE_BUNDLE_ID,
                MODULE_ENTRY,
                List.of(YmclModuleSupport.PERMISSION_DATA_FETCH, YmclModuleSupport.PERMISSION_ACTION_EXECUTE));
    }

    @Override
    public String providerCode() {
        return MinecraftActivityProofPlugin.CODE;
    }

    @Override
    public List<YmclPageDescriptor> pages() {
        YmclModuleSupport.YmclModuleBundle module = moduleBundle();
        if (module != null) {
            // module 页面自持数据拉取；dataSource 仍须绑定——启动器首页卡片
            // 「全部」与裸数据源路由按它反查宿主页面，置空会回退到合成旧渲染页。
            // DomainPageHost 对 module 渲染器跳过信封拉取，不会重复取数。
            return List.of(new YmclPageDescriptor(
                    PAGE_SQUARE,
                    "module",
                    "活动",
                    "i-ri:calendar-event-line",
                    providerCode() + "." + DATA_SOURCE_ACTIVITIES,
                    20,
                    MinecraftActivityProofPlugin.VIEW_PERMISSION,
                    Map.of(),
                    module.descriptor()
            ));
        }
        // 资源缺失时降级为内置 card-grid 渲染器，页面不失联。
        return List.of(new YmclPageDescriptor(
                PAGE_SQUARE,
                "card-grid",
                "活动",
                "i-ri:calendar-event-line",
                providerCode() + "." + DATA_SOURCE_ACTIVITIES,
                20,
                MinecraftActivityProofPlugin.VIEW_PERMISSION,
                Map.of(),
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
                DATA_SOURCE_ACTIVITIES,
                "活动",
                DATA_SOURCE_SCHEMA_VERSION,
                DATA_SOURCE_CACHE_TTL
        ));
    }

    @Override
    public Object fetchData(String sourceCode, YmclDataContext context) {
        if (!DATA_SOURCE_ACTIVITIES.equals(sourceCode) || context.userId() == null) {
            return emptyEnvelope();
        }
        int page = Math.max(1, context.page());
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, context.pageSize()));
        ActivityProofPageDTO<UserActivityDTO> result =
                appService.userActivities(String.valueOf(context.userId()), page, pageSize);
        List<Map<String, Object>> records = new ArrayList<>();
        for (UserActivityDTO dto : result.records()) {
            records.add(toCard(dto));
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", DATA_SOURCE_SCHEMA_VERSION);
        envelope.put("records", records);
        envelope.put("total", result.total());
        envelope.put("actions", List.of(
                action("refresh", "刷新", "client:reload", false, Map.of())
        ));
        envelope.put("itemActions", List.of(
                action("join", "报名", "server:" + providerCode() + ":join", true,
                        Map.of("activityId", "{{item.id}}")),
                action("cancel", "取消报名", "server:" + providerCode() + ":cancel", false,
                        Map.of("activityId", "{{item.id}}"))
        ));
        envelope.put("cacheTtl", DATA_SOURCE_CACHE_TTL);
        return envelope;
    }

    @Override
    public Object executeAction(String actionCode, Map<String, Object> params, YmclDataContext context) {
        if (context.userId() == null) {
            return Map.of("toast", "请先登录", "refresh", false);
        }
        String activityId = textParam(params, "activityId");
        if (activityId == null || activityId.isBlank()) {
            return Map.of("toast", "缺少活动参数", "refresh", false);
        }
        String userId = String.valueOf(context.userId());
        try {
            UserActivityDTO dto = switch (actionCode) {
                case "join" -> appService.joinActivity(activityId, userId);
                case "cancel" -> appService.cancelActivity(activityId, userId);
                default -> null;
            };
            if (dto == null) {
                return Map.of("toast", "未知动作：" + actionCode, "refresh", false);
            }
            boolean joined = "JOINED".equals(dto.participationStatus());
            return Map.of(
                    "toast", (joined ? "已报名活动：" : "已取消报名：") + dto.title(),
                    "refresh", true);
        } catch (RuntimeException error) {
            return Map.of("toast", error.getMessage() == null ? "操作失败" : error.getMessage(),
                    "refresh", false);
        }
    }

    private Map<String, Object> toCard(UserActivityDTO dto) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", dto.id());
        card.put("title", dto.title());
        String statusText = statusText(dto);
        card.put("statusText", statusText);
        card.put("timeText", timeText(dto));
        card.put("participantCount", dto.participantCount());
        boolean joined = "JOINED".equals(dto.participationStatus());
        card.put("joined", joined);
        card.put("joinable", joinable(dto));
        card.put("summary", summary(dto, statusText, joined));
        if (dto.coverUrl() != null && !dto.coverUrl().isBlank()) {
            card.put("cover", dto.coverUrl());
        }
        // 详情视图字段：列表 DTO 本就携带，透传后 module 详情页无需二次取数。
        card.put("description", dto.description() == null ? "" : dto.description());
        card.put("requirements", dto.requirements() == null ? List.of() : dto.requirements());
        card.put("deptRestricted", dto.deptRestricted());
        card.put("allowedDeptNames",
                dto.allowedDeptNames() == null ? List.of() : dto.allowedDeptNames());
        card.put("signupStart", dto.signupStart());
        card.put("signupEnd", dto.signupEnd());
        card.put("activityStart", dto.activityStart());
        card.put("activityEnd", dto.activityEnd());
        card.put("joinedAt", dto.joinedAt());
        card.put("eligible", dto.eligible());
        card.put("joinDisabledReason",
                dto.joinDisabledReason() == null ? "" : dto.joinDisabledReason());
        card.put("verifyStatus", dto.verifyStatus() == null ? "" : dto.verifyStatus());
        card.put("verifyNote", dto.verifyNote() == null ? "" : dto.verifyNote());
        return card;
    }

    private static String summary(UserActivityDTO dto, String statusText, boolean joined) {
        List<String> parts = new ArrayList<>();
        parts.add(statusText);
        String time = timeText(dto);
        if (!time.isBlank()) {
            parts.add(time);
        }
        parts.add(dto.participantCount() + " 人参与");
        if (joined) {
            parts.add("已报名");
        } else {
            if (dto.verifyStatus() != null && !dto.verifyStatus().isBlank()
                    && !"UNVERIFIED".equals(dto.verifyStatus())) {
                parts.add("PASSED".equals(dto.verifyStatus()) ? "核验通过" : "核验未通过");
            }
            if (dto.joinDisabledReason() != null && !dto.joinDisabledReason().isBlank()) {
                parts.add(dto.joinDisabledReason());
            }
        }
        return String.join(" · ", parts);
    }

    private static String statusText(UserActivityDTO dto) {
        long now = System.currentTimeMillis();
        boolean ended = "CLOSED".equals(dto.status())
                || (dto.activityEnd() > 0 && now > dto.activityEnd());
        if (ended) {
            return "已结束";
        }
        boolean started = dto.activityStart() > 0 && now >= dto.activityStart();
        if (started) {
            return "进行中";
        }
        return signupOpen(dto, now) ? "报名中" : "即将开始";
    }

    private static boolean signupOpen(UserActivityDTO dto, long now) {
        return (dto.signupStart() <= 0 || now >= dto.signupStart())
                && (dto.signupEnd() <= 0 || now <= dto.signupEnd());
    }

    private static boolean joinable(UserActivityDTO dto) {
        long now = System.currentTimeMillis();
        return "PUBLISHED".equals(dto.status())
                && !(dto.activityEnd() > 0 && now > dto.activityEnd())
                && signupOpen(dto, now)
                && dto.eligible()
                && !"JOINED".equals(dto.participationStatus());
    }

    private static String timeText(UserActivityDTO dto) {
        if (dto.activityStart() <= 0) {
            return "";
        }
        String start = TIME_FORMATTER.format(
                Instant.ofEpochMilli(dto.activityStart()).atZone(ZoneId.systemDefault()));
        if (dto.activityEnd() <= dto.activityStart()) {
            return start + " 起";
        }
        String end = TIME_FORMATTER.format(
                Instant.ofEpochMilli(dto.activityEnd()).atZone(ZoneId.systemDefault()));
        return start + " 至 " + end;
    }

    private static String textParam(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        return value == null ? null : String.valueOf(value);
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

    private static Map<String, Object> emptyEnvelope() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", DATA_SOURCE_SCHEMA_VERSION);
        envelope.put("records", List.of());
        envelope.put("total", 0);
        envelope.put("actions", List.of());
        envelope.put("itemActions", List.of());
        envelope.put("cacheTtl", DATA_SOURCE_CACHE_TTL);
        return envelope;
    }
}
