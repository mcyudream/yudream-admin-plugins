package online.yudream.plugin.qqbotautomation.interfaces.http;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicyOverride;
import online.yudream.plugin.qqbotautomation.application.dto.GroupAliasRequest;
import online.yudream.plugin.qqbotautomation.application.dto.GroupIdentifyRequest;
import online.yudream.plugin.qqbotautomation.application.dto.MediaJobTestRequest;
import online.yudream.plugin.qqbotautomation.application.dto.MediaStorageSettingsRequest;
import online.yudream.plugin.qqbotautomation.application.service.AutomationPolicyService;
import online.yudream.plugin.qqbotautomation.application.service.GroupAliasService;
import online.yudream.plugin.qqbotautomation.application.service.MediaJobService;
import online.yudream.plugin.qqbotautomation.application.service.MediaStorageSettings;
import online.yudream.plugin.qqbotautomation.interfaces.support.JsonSupport;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class QqbotAutomationHttpFacade {
    private final AutomationPolicyService policies; private final MediaJobService mediaJobs; private final FrameworkServices framework;
    private final MediaStorageSettings mediaSettings;
    private final GroupAliasService groupAliases;
    public QqbotAutomationHttpFacade(AutomationPolicyService policies, MediaJobService mediaJobs, FrameworkServices framework,
                                     MediaStorageSettings mediaSettings, GroupAliasService groupAliases) {
        this.policies = policies; this.mediaJobs = mediaJobs; this.framework = framework; this.mediaSettings = mediaSettings;
        this.groupAliases = groupAliases;
    }
    public PluginHttpResponse policies() { return PluginHttpResponse.ok(policies.list()); }
    public PluginHttpResponse policy(PluginHttpRequest request) { return PluginHttpResponse.ok(policies.get(query(request, "connectionId"), query(request, "channelId"))); }
    public PluginHttpResponse save(PluginHttpRequest request) {
        AutomationPolicy policy = JsonSupport.read(request.body(), AutomationPolicy.class);
        requireKnownGroup(policy.connectionId(), policy.channelId());
        return PluginHttpResponse.ok(policies.save(policy));
    }
    public PluginHttpResponse defaults(PluginHttpRequest request) {
        String connectionId = query(request, "connectionId");
        requireKnownConnection(connectionId);
        return PluginHttpResponse.ok(policies.getDefaults(connectionId));
    }
    public PluginHttpResponse saveDefaults(PluginHttpRequest request) {
        AutomationPolicy policy = JsonSupport.read(request.body(), AutomationPolicy.class);
        requireKnownConnection(policy.connectionId());
        return PluginHttpResponse.ok(policies.saveDefaults(policy));
    }
    public PluginHttpResponse overrides(PluginHttpRequest request) {
        String connectionId = query(request, "connectionId");
        requireKnownConnection(connectionId);
        return PluginHttpResponse.ok(Map.of(
                "records", policies.pageOverrides(connectionId, number(request, "page", 1), number(request, "size", 10)),
                "total", policies.countOverrides(connectionId)
        ));
    }
    public PluginHttpResponse override(PluginHttpRequest request) {
        String connectionId = query(request, "connectionId");
        String channelId = pathSegment(request.path(), 2);
        requireKnownGroup(connectionId, channelId);
        return PluginHttpResponse.ok(policies.getOverride(connectionId, channelId).orElse(null));
    }
    public PluginHttpResponse saveOverride(PluginHttpRequest request) {
        AutomationPolicyOverride override = JsonSupport.read(request.body(), AutomationPolicyOverride.class);
        requireKnownGroup(override.connectionId(), override.channelId());
        return PluginHttpResponse.ok(policies.saveOverride(override));
    }
    public PluginHttpResponse deleteOverride(PluginHttpRequest request) {
        String connectionId = query(request, "connectionId");
        String channelId = pathSegment(request.path(), 2);
        requireKnownGroup(connectionId, channelId);
        policies.deleteOverride(connectionId, channelId);
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }
    public PluginHttpResponse startMediaTest(PluginHttpRequest request) {
        MediaJobTestRequest test = JsonSupport.read(request.body(), MediaJobTestRequest.class);
        requireKnownGroup(test.connectionId(), test.channelId());
        return PluginHttpResponse.ok(Map.of("id", mediaJobs.startTest(test), "trigger", "MANUAL_TEST"));
    }
    public PluginHttpResponse connections() { return PluginHttpResponse.ok(framework.messaging().connections()); }
    public PluginHttpResponse groups(PluginHttpRequest request) {
        String connectionId = query(request, "connectionId");
        requireKnownConnection(connectionId);
        return PluginHttpResponse.ok(groupAliases.list(connectionId));
    }
    public PluginHttpResponse saveGroupAlias(PluginHttpRequest request) {
        GroupAliasRequest body = JsonSupport.read(request.body(), GroupAliasRequest.class);
        requireKnownConnection(body.connectionId());
        return PluginHttpResponse.ok(groupAliases.save(body.connectionId(), body.channelId(), body.alias()));
    }
    public PluginHttpResponse identifyGroup(PluginHttpRequest request) {
        GroupIdentifyRequest body = JsonSupport.read(request.body(), GroupIdentifyRequest.class);
        requireKnownConnection(body.connectionId());
        return PluginHttpResponse.ok(groupAliases.identify(body.connectionId(), body.channelId()));
    }
    public PluginHttpResponse aiOptions() { return PluginHttpResponse.ok(framework.ai().providers()); }
    /** 风险告警管理员的带标签用户选择器；SPI 无总数统计，多取一条探测下一页。 */
    public PluginHttpResponse users(PluginHttpRequest request) {
        if (framework.users() == null) {
            return PluginHttpResponse.ok(Map.of("records", java.util.List.of(), "total", 0));
        }
        int page = Math.max(number(request, "page", 1), 1);
        int size = Math.min(Math.max(number(request, "size", 10), 1), 50);
        String keyword = query(request, "keyword");
        java.util.List<online.yudream.base.plugin.spi.system.user.PluginUserOption> probe = framework.users()
                .searchUsers(keyword == null || keyword.isBlank() ? null : keyword.trim(), null, page, size + 1);
        boolean hasMore = probe.size() > size;
        java.util.List<Map<String, Object>> records = probe.stream().limit(size).map(option -> {
            Map<String, Object> item = new java.util.LinkedHashMap<String, Object>();
            item.put("id", option.id());
            item.put("username", option.username() == null ? "" : option.username());
            item.put("nickname", option.nickname() == null ? "" : option.nickname());
            item.put("deptNames", option.deptNames() == null ? java.util.List.of() : option.deptNames());
            return item;
        }).toList();
        long total = hasMore ? (long) page * size + 1 : (long) (page - 1) * size + records.size();
        return PluginHttpResponse.ok(Map.of("records", records, "total", total));
    }
    public PluginHttpResponse mediaJob(PluginHttpRequest request) { return PluginHttpResponse.ok(mediaJobs.find(pathSegment(request.path(), 2))); }
    public PluginHttpResponse mediaJobs(PluginHttpRequest request) { return PluginHttpResponse.ok(Map.of("records", mediaJobs.page(number(request, "page", 1), number(request, "size", 10)), "total", mediaJobs.total())); }
    public PluginHttpResponse clearMediaJobs() { return PluginHttpResponse.ok(Map.of("deleted", mediaJobs.clear())); }
    public PluginHttpResponse mediaSettings() { return PluginHttpResponse.ok(mediaSettings.view()); }
    public PluginHttpResponse saveMediaSettings(PluginHttpRequest request) {
        MediaStorageSettingsRequest settings = JsonSupport.read(request.body(), MediaStorageSettingsRequest.class);
        return PluginHttpResponse.ok(mediaSettings.save(settings.hostDirectory(), settings.containerDirectory()));
    }
    private void requireKnownConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) throw new IllegalArgumentException("connectionId cannot be blank");
        boolean known = framework.messaging().connections().stream().anyMatch(item -> connectionId.equals(item.id()));
        if (!known) throw new IllegalArgumentException("请选择有效的消息连接");
    }
    private void requireKnownGroup(String connectionId, String channelId) {
        requireKnownConnection(connectionId);
        if (channelId == null || channelId.isBlank()) throw new IllegalArgumentException("请填写群 ID");
    }
    private String pathSegment(String path, int index) {
        String[] segments = (path == null ? "" : path).replaceFirst("^/+", "").split("/");
        return index >= 0 && index < segments.length ? URLDecoder.decode(segments[index], StandardCharsets.UTF_8) : null;
    }
    private String query(PluginHttpRequest request, String key) { var values = request.query().get(key); return values == null || values.isEmpty() ? null : values.getFirst(); }
    private int number(PluginHttpRequest request, String key, int fallback) { try { return Integer.parseInt(query(request, key)); } catch (Exception ignored) { return fallback; } }
}
