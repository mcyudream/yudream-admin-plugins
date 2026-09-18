package online.yudream.base.plugin.projectprogress.infrastructure.launcher;

import online.yudream.base.plugin.projectprogress.application.dto.ProjectMemberStatsDTO;
import online.yudream.base.plugin.projectprogress.application.dto.ProjectPersonalStatsDTO;
import online.yudream.base.plugin.projectprogress.application.dto.ProjectProgressProjectDTO;
import online.yudream.base.plugin.projectprogress.application.dto.ProjectUserOptionDTO;
import online.yudream.base.plugin.projectprogress.application.dto.ProjectWorkDetailDTO;
import online.yudream.base.plugin.projectprogress.application.service.ProjectProgressAppService;
import online.yudream.base.plugin.projectprogress.bootstrap.ProjectProgressPlugin;
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
 * project-progress 向 YMCL 适配器（ymcl-adapter）贡献任务页与个人统计
 * （YAP §3 扩展点）。
 *
 * 「我的任务」「可认领任务」走 card-grid 渲染器；认领与 Minecraft 在线时长
 * 打卡通过 server 动作（kind {@code server:{providerCode}:{actionCode}}，
 * 参数模板 {{item.x}}）回传 executeAction 执行；my-stats 供启动器首页
 * stats 变体数据卡展示。ymcl-adapter 缺失时由 bootstrap 捕获 LinkageError 降级。
 */
public class ProjectProgressYmclContributionProvider implements YmclContributionProvider {

    public static final String PAGE_MY_TASKS = "progress.my-tasks";
    public static final String PAGE_CLAIMABLE = "progress.claimable";
    public static final String DATA_SOURCE_MY_TASKS = "my-tasks";
    public static final String DATA_SOURCE_CLAIMABLE = "claimable-tasks";
    public static final String DATA_SOURCE_MY_STATS = "my-stats";
    public static final String DATA_SOURCE_PROJECT_DETAIL = "project-detail";
    public static final int DATA_SOURCE_SCHEMA_VERSION = 1;
    public static final int DATA_SOURCE_CACHE_TTL = 60;
    private static final int MAX_PAGE_SIZE = 50;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 任务页 module 资源（YAP §6.8）：jar 内 ESM，经适配器 bundle 通道下发。 */
    private static final String MODULE_TASKS_RESOURCE = "/ymcl-pages/my-tasks-page.js";
    private static final String MODULE_TASKS_BUNDLE_ID = "ymcl-progress-tasks";
    private static final String MODULE_TASKS_ENTRY = "my-tasks-page.js";
    private static final String MODULE_CLAIMABLE_RESOURCE = "/ymcl-pages/claimable-page.js";
    private static final String MODULE_CLAIMABLE_BUNDLE_ID = "ymcl-progress-claimable";
    private static final String MODULE_CLAIMABLE_ENTRY = "claimable-page.js";

    private final ProjectProgressAppService appService;

    public ProjectProgressYmclContributionProvider(ProjectProgressAppService appService) {
        this.appService = appService;
    }

    private static YmclModuleSupport.YmclModuleBundle moduleBundle(
            String resource, String bundleId, String entry) {
        return YmclModuleSupport.fromResource(
                ProjectProgressYmclContributionProvider.class,
                resource,
                bundleId,
                entry,
                List.of(YmclModuleSupport.PERMISSION_DATA_FETCH, YmclModuleSupport.PERMISSION_ACTION_EXECUTE));
    }

    @Override
    public String providerCode() {
        return ProjectProgressPlugin.CODE;
    }

    @Override
    public List<YmclPageDescriptor> pages() {
        YmclModuleSupport.YmclModuleBundle tasksModule =
                moduleBundle(MODULE_TASKS_RESOURCE, MODULE_TASKS_BUNDLE_ID, MODULE_TASKS_ENTRY);
        YmclModuleSupport.YmclModuleBundle claimableModule =
                moduleBundle(MODULE_CLAIMABLE_RESOURCE, MODULE_CLAIMABLE_BUNDLE_ID, MODULE_CLAIMABLE_ENTRY);
        // module 页面自持数据拉取；dataSource 仍须绑定——启动器首页卡片
        // 「全部」与裸数据源路由按它反查宿主页面，置空会回退到合成旧渲染页。
        // DomainPageHost 对 module 渲染器跳过信封拉取，不会重复取数；
        // 资源缺失时降级为内置 card-grid 渲染器，页面不失联。
        return List.of(
                tasksModule != null
                        ? new YmclPageDescriptor(
                                PAGE_MY_TASKS,
                                "module",
                                "我的任务",
                                "i-ri:list-check-3",
                                providerCode() + "." + DATA_SOURCE_MY_TASKS,
                                30,
                                ProjectProgressPlugin.CHECK_IN_PERMISSION,
                                Map.of(),
                                tasksModule.descriptor()
                        )
                        : new YmclPageDescriptor(
                                PAGE_MY_TASKS,
                                "card-grid",
                                "我的任务",
                                "i-ri:list-check-3",
                                providerCode() + "." + DATA_SOURCE_MY_TASKS,
                                30,
                                ProjectProgressPlugin.CHECK_IN_PERMISSION,
                                Map.of(),
                                null
                        ),
                claimableModule != null
                        ? new YmclPageDescriptor(
                                PAGE_CLAIMABLE,
                                "module",
                                "可认领任务",
                                "i-ri:checkbox-circle-line",
                                providerCode() + "." + DATA_SOURCE_CLAIMABLE,
                                40,
                                ProjectProgressPlugin.CHECK_IN_PERMISSION,
                                Map.of(),
                                claimableModule.descriptor()
                        )
                        : new YmclPageDescriptor(
                                PAGE_CLAIMABLE,
                                "card-grid",
                                "可认领任务",
                                "i-ri:checkbox-circle-line",
                                providerCode() + "." + DATA_SOURCE_CLAIMABLE,
                                40,
                                ProjectProgressPlugin.CHECK_IN_PERMISSION,
                                Map.of(),
                                null
                        )
        );
    }

    @Override
    public List<YmclBundleContribution> bundles() {
        List<YmclBundleContribution> contributions = new ArrayList<>();
        YmclModuleSupport.YmclModuleBundle tasksModule =
                moduleBundle(MODULE_TASKS_RESOURCE, MODULE_TASKS_BUNDLE_ID, MODULE_TASKS_ENTRY);
        if (tasksModule != null) {
            contributions.add(tasksModule.contribution());
        }
        YmclModuleSupport.YmclModuleBundle claimableModule =
                moduleBundle(MODULE_CLAIMABLE_RESOURCE, MODULE_CLAIMABLE_BUNDLE_ID, MODULE_CLAIMABLE_ENTRY);
        if (claimableModule != null) {
            contributions.add(claimableModule.contribution());
        }
        return contributions;
    }

    @Override
    public List<YmclDataSourceDescriptor> dataSources() {
        return List.of(
                new YmclDataSourceDescriptor(DATA_SOURCE_MY_TASKS, "我的任务", DATA_SOURCE_SCHEMA_VERSION, DATA_SOURCE_CACHE_TTL),
                new YmclDataSourceDescriptor(DATA_SOURCE_CLAIMABLE, "可认领任务", DATA_SOURCE_SCHEMA_VERSION, DATA_SOURCE_CACHE_TTL),
                new YmclDataSourceDescriptor(DATA_SOURCE_MY_STATS, "个人统计", DATA_SOURCE_SCHEMA_VERSION, DATA_SOURCE_CACHE_TTL),
                new YmclDataSourceDescriptor(DATA_SOURCE_PROJECT_DETAIL, "项目详情", DATA_SOURCE_SCHEMA_VERSION, DATA_SOURCE_CACHE_TTL)
        );
    }

    @Override
    public Object fetchData(String sourceCode, YmclDataContext context) {
        if (context.userId() == null) {
            return emptyEnvelope(sourceCode);
        }
        String userId = String.valueOf(context.userId());
        return switch (sourceCode) {
            case DATA_SOURCE_MY_TASKS -> taskEnvelope(sourceCode,
                    appService.myTasks(userId, safePage(context.page()), safePageSize(context.pageSize())));
            case DATA_SOURCE_CLAIMABLE -> claimableEnvelope(sourceCode,
                    appService.claimableTasks(userId, safePage(context.page()), safePageSize(context.pageSize())));
            case DATA_SOURCE_MY_STATS -> statsEnvelope(userId);
            case DATA_SOURCE_PROJECT_DETAIL -> projectDetailEnvelope(context);
            default -> emptyEnvelope(sourceCode);
        };
    }

    @Override
    public Object executeAction(String actionCode, Map<String, Object> params, YmclDataContext context) {
        if (context.userId() == null) {
            return Map.of("toast", "请先登录", "refresh", false);
        }
        String detailId = textParam(params, "detailId");
        if (detailId == null || detailId.isBlank()) {
            return Map.of("toast", "缺少任务参数", "refresh", false);
        }
        String userId = String.valueOf(context.userId());
        try {
            switch (actionCode) {
                case "claim" -> {
                    ProjectWorkDetailDTO dto = appService.claim(detailId, userId);
                    return Map.of("toast", "已认领任务：" + dto.title(), "refresh", true);
                }
                case "minecraft-check-in" -> {
                    appService.minecraftCheckIn(detailId, userId);
                    return Map.of("toast", "打卡成功", "refresh", true);
                }
                default -> {
                    return Map.of("toast", "未知动作：" + actionCode, "refresh", false);
                }
            }
        } catch (RuntimeException error) {
            return Map.of("toast", error.getMessage() == null ? "操作失败" : error.getMessage(),
                    "refresh", false);
        }
    }

    private Map<String, Object> taskEnvelope(String sourceCode, List<ProjectWorkDetailDTO> details) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (ProjectWorkDetailDTO dto : details) {
            records.add(taskCard(dto));
        }
        return envelope(sourceCode, records, records.size(), List.of(
                action("refresh", "刷新", "client:reload", false, Map.of())
        ), List.of(
                action("check-in", "MC 时长打卡", "server:" + providerCode() + ":minecraft-check-in", true,
                        Map.of("detailId", "{{item.id}}"))
        ));
    }

    private Map<String, Object> claimableEnvelope(String sourceCode, List<ProjectWorkDetailDTO> details) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (ProjectWorkDetailDTO dto : details) {
            Map<String, Object> card = taskCard(dto);
            card.put("summary", (dto.description() == null || dto.description().isBlank()
                    ? "可认领任务" : dto.description()) + " · " + statusText(dto.statusCode()));
            records.add(card);
        }
        return envelope(sourceCode, records, records.size(), List.of(
                action("refresh", "刷新", "client:reload", false, Map.of())
        ), List.of(
                action("claim", "认领任务", "server:" + providerCode() + ":claim", true,
                        Map.of("detailId", "{{item.id}}"))
        ));
    }

    private Map<String, Object> statsEnvelope(String userId) {
        ProjectPersonalStatsDTO stats = appService.personalStats(userId);
        List<ProjectWorkDetailDTO> claimable = appService.claimableTasks(userId, 1, MAX_PAGE_SIZE);
        List<Map<String, Object>> records = List.of(
                stat("进行中任务", stats.assignedDetails()),
                stat("已完成任务", stats.completedDetails()),
                stat("待验收", stats.pendingAcceptanceDetails()),
                stat("可认领任务", claimable.size()),
                stat("累计打卡", stats.checkIns())
        );
        Map<String, Object> envelope = envelope(DATA_SOURCE_MY_STATS, records, records.size(),
                List.of(action("refresh", "刷新", "client:reload", false, Map.of())), List.of());
        return envelope;
    }

    private Map<String, Object> taskCard(ProjectWorkDetailDTO dto) {
        return taskCard(dto, Map.of());
    }

    private Map<String, Object> taskCard(ProjectWorkDetailDTO dto, Map<String, String> statusLabels) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", dto.id());
        card.put("title", dto.title());
        card.put("projectId", dto.projectId());
        card.put("statusCode", dto.statusCode());
        String label = statusLabels.get(dto.statusCode());
        card.put("statusText", label == null ? statusText(dto.statusCode()) : label);
        card.put("dueText", dueText(dto.dueAt()));
        card.put("pendingAcceptance", dto.pendingAcceptance());
        card.put("summary", summary(dto));
        // 详情视图字段：透传后 module 任务详情页无需二次取数。
        card.put("description", dto.description() == null ? "" : dto.description());
        card.put("assignmentMode", dto.assignmentMode() == null ? "" : dto.assignmentMode());
        card.put("requiredAssigneeCount", dto.requiredAssigneeCount());
        card.put("assigneeCount", dto.assigneeUserIds() == null ? 0 : dto.assigneeUserIds().size());
        card.put("published", dto.published());
        card.put("acceptanceSummary",
                dto.acceptanceSummary() == null ? "" : dto.acceptanceSummary());
        card.put("dueAt", dto.dueAt() == null ? 0L : dto.dueAt());
        card.put("createdAt", dto.createdAt());
        card.put("updatedAt", dto.updatedAt());
        return card;
    }

    /**
     * 项目详情信封（YAP §6.6 扩展）：records 为项目下的任务卡片（状态文案按
     * 项目自定义状态表翻译），项目本身放在 project 键，成员统计放在
     * memberStats 键，module 详情视图一次取全。
     */
    private Map<String, Object> projectDetailEnvelope(YmclDataContext context) {
        String projectId = context.param("id");
        if (projectId == null) {
            return emptyEnvelope(DATA_SOURCE_PROJECT_DETAIL);
        }
        ProjectProgressProjectDTO project;
        try {
            project = appService.project(projectId);
        } catch (RuntimeException notFound) {
            return emptyEnvelope(DATA_SOURCE_PROJECT_DETAIL);
        }
        if (project == null) {
            return emptyEnvelope(DATA_SOURCE_PROJECT_DETAIL);
        }
        Map<String, String> statusLabels = new LinkedHashMap<>();
        if (project.statuses() != null) {
            for (ProjectProgressProjectDTO.StatusDTO status : project.statuses()) {
                statusLabels.put(status.code(), status.label());
            }
        }
        Map<String, String> userNames = userNames(project);

        List<Map<String, Object>> records = new ArrayList<>();
        for (ProjectWorkDetailDTO dto : appService.details(projectId, 1, MAX_PAGE_SIZE)) {
            records.add(taskCard(dto, statusLabels));
        }
        List<Map<String, Object>> members = new ArrayList<>();
        for (ProjectMemberStatsDTO stat : appService.projectMemberStats(projectId)) {
            members.add(memberCard(stat, userNames));
        }

        Map<String, Object> view = envelope(DATA_SOURCE_PROJECT_DETAIL, records, records.size(),
                List.of(action("refresh", "刷新", "client:reload", false, Map.of())),
                List.of(
                        action("claim", "认领任务", "server:" + providerCode() + ":claim", true,
                                Map.of("detailId", "{{item.id}}")),
                        action("check-in", "MC 时长打卡", "server:" + providerCode() + ":minecraft-check-in",
                                false, Map.of("detailId", "{{item.id}}"))
                ));
        view.put("project", projectCard(project, userNames));
        view.put("memberStats", members);
        return view;
    }

    /** 管理者+成员+任务负责人的 id → 展示名（昵称优先）映射。 */
    private Map<String, String> userNames(ProjectProgressProjectDTO project) {
        List<String> ids = new ArrayList<>();
        if (project.managerUserIds() != null) {
            ids.addAll(project.managerUserIds());
        }
        if (project.memberUserIds() != null) {
            ids.addAll(project.memberUserIds());
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (ProjectUserOptionDTO user : appService.usersByIds(ids)) {
            String display = user.nickname() == null || user.nickname().isBlank()
                    ? user.username() : user.nickname();
            names.put(user.id(), display == null || display.isBlank() ? user.id() : display);
        }
        return names;
    }

    private Map<String, Object> projectCard(
            ProjectProgressProjectDTO project, Map<String, String> userNames) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", project.id());
        card.put("name", project.name());
        card.put("description", project.description() == null ? "" : project.description());
        card.put("enabled", project.enabled());
        List<String> managers = new ArrayList<>();
        if (project.managerUserIds() != null) {
            for (String managerId : project.managerUserIds()) {
                managers.add(userNames.getOrDefault(managerId, managerId));
            }
        }
        card.put("managerNames", managers);
        card.put("memberCount", project.memberUserIds() == null ? 0 : project.memberUserIds().size());
        card.put("createdAt", project.createdAt());
        card.put("updatedAt", project.updatedAt());
        ProjectProgressProjectDTO.MinecraftPolicyDTO policy = project.minecraftPolicy();
        if (policy != null && policy.enabled()) {
            card.put("minecraftPolicyText",
                    "MC 在线打卡：满 " + policy.requiredOnlineMinutes() + " 分钟"
                            + (policy.includeAfk() ? "（含挂机）" : "")
                            + (policy.autoCheckInEnabled() ? "，自动打卡" : ""));
        } else {
            card.put("minecraftPolicyText", "");
        }
        return card;
    }

    private Map<String, Object> memberCard(
            ProjectMemberStatsDTO stat, Map<String, String> userNames) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("userId", stat.userId());
        card.put("name", userNames.getOrDefault(stat.userId(), stat.userId()));
        card.put("assignedDetails", stat.assignedDetails());
        card.put("completedDetails", stat.completedDetails());
        card.put("pendingAcceptanceDetails", stat.pendingAcceptanceDetails());
        card.put("checkIns", stat.checkIns());
        return card;
    }

    private static String summary(ProjectWorkDetailDTO dto) {
        List<String> parts = new ArrayList<>();
        parts.add(statusText(dto.statusCode()));
        if (dto.pendingAcceptance()) {
            parts.add("待验收");
        }
        String due = dueText(dto.dueAt());
        if (!due.isBlank()) {
            parts.add(due);
        }
        String description = dto.description();
        if (description != null && !description.isBlank()) {
            parts.add(plainText(description));
        }
        return String.join(" · ", parts);
    }

    private static String statusText(String statusCode) {
        if (statusCode == null || statusCode.isBlank()) {
            return "未知状态";
        }
        return switch (statusCode) {
            case "TODO" -> "待处理";
            case "REVIEWING" -> "待验收";
            case "REPAIRING" -> "返工中";
            case "DONE" -> "已完成";
            default -> statusCode;
        };
    }

    private static String dueText(Long dueAt) {
        if (dueAt == null || dueAt <= 0) {
            return "";
        }
        return "截止 " + TIME_FORMATTER.format(
                Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()));
    }

    /** 描述可能携带 Markdown 语法，启动器卡片按纯文本展示，压掉常见标记。 */
    private static String plainText(String description) {
        String text = description;
        if (text.length() > 80) {
            text = text.substring(0, 80) + "…";
        }
        return text.replace("\r", " ").replace("\n", " ");
    }

    private static Map<String, Object> stat(String label, int value) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("label", label);
        record.put("value", value);
        return record;
    }

    private Map<String, Object> envelope(
            String sourceCode, List<Map<String, Object>> records, long total,
            List<Map<String, Object>> actions, List<Map<String, Object>> itemActions) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("schemaVersion", DATA_SOURCE_SCHEMA_VERSION);
        view.put("records", records);
        view.put("total", total);
        view.put("actions", actions);
        view.put("itemActions", itemActions);
        view.put("cacheTtl", DATA_SOURCE_CACHE_TTL);
        return view;
    }

    private Map<String, Object> emptyEnvelope(String sourceCode) {
        return envelope(sourceCode, List.of(), 0, List.of(), List.of());
    }

    private static int safePage(int page) {
        return Math.max(1, page);
    }

    private static int safePageSize(int pageSize) {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
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
}
