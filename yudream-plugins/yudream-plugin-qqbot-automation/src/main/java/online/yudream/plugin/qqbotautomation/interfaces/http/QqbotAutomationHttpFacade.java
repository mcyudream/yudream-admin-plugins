package online.yudream.plugin.qqbotautomation.interfaces.http;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicyOverride;
import online.yudream.plugin.qqbotautomation.application.dto.MediaJobTestRequest;
import online.yudream.plugin.qqbotautomation.application.service.AutomationPolicyService;
import online.yudream.plugin.qqbotautomation.application.service.MediaJobService;
import online.yudream.plugin.qqbotautomation.interfaces.support.JsonSupport;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class QqbotAutomationHttpFacade {
    private final AutomationPolicyService policies; private final MediaJobService mediaJobs; private final FrameworkServices framework;
    public QqbotAutomationHttpFacade(AutomationPolicyService policies, MediaJobService mediaJobs, FrameworkServices framework) { this.policies = policies; this.mediaJobs = mediaJobs; this.framework = framework; }
    public PluginHttpResponse policies() { return PluginHttpResponse.ok(policies.list()); }
    public PluginHttpResponse policy(PluginHttpRequest request) { return PluginHttpResponse.ok(policies.get(query(request, "connectionId"), query(request, "channelId"))); }
    public PluginHttpResponse save(PluginHttpRequest request) {
        AutomationPolicy policy = JsonSupport.read(request.body(), AutomationPolicy.class);
        boolean connectionKnown = framework.messaging().connections().stream().anyMatch(item -> item.id().equals(policy.connectionId()));
        boolean groupKnown = connectionKnown && framework.messaging().groups(policy.connectionId()).stream().anyMatch(item -> item.id().equals(policy.channelId()));
        if (!groupKnown) throw new IllegalArgumentException("请选择当前连接中的有效群聊");
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
    public PluginHttpResponse groups(PluginHttpRequest request) { return PluginHttpResponse.ok(framework.messaging().groups(query(request, "connectionId"))); }
    public PluginHttpResponse aiOptions() { return PluginHttpResponse.ok(framework.ai().providers()); }
    public PluginHttpResponse mediaJob(PluginHttpRequest request) { return PluginHttpResponse.ok(mediaJobs.find(pathSegment(request.path(), 2))); }
    public PluginHttpResponse mediaJobs(PluginHttpRequest request) { return PluginHttpResponse.ok(Map.of("records", mediaJobs.page(number(request, "page", 1), number(request, "size", 10)), "total", mediaJobs.total())); }
    private void requireKnownConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) throw new IllegalArgumentException("connectionId cannot be blank");
        boolean known = framework.messaging().connections().stream().anyMatch(item -> connectionId.equals(item.id()));
        if (!known) throw new IllegalArgumentException("Select a valid messaging connection");
    }
    private void requireKnownGroup(String connectionId, String channelId) {
        requireKnownConnection(connectionId);
        if (channelId == null || channelId.isBlank()) throw new IllegalArgumentException("channelId cannot be blank");
        boolean known = framework.messaging().groups(connectionId).stream().anyMatch(item -> channelId.equals(item.id()));
        if (!known) throw new IllegalArgumentException("Select a valid group in the selected connection");
    }
    private String pathSegment(String path, int index) {
        String[] segments = (path == null ? "" : path).replaceFirst("^/+", "").split("/");
        return index >= 0 && index < segments.length ? URLDecoder.decode(segments[index], StandardCharsets.UTF_8) : null;
    }
    private String query(PluginHttpRequest request, String key) { var values = request.query().get(key); return values == null || values.isEmpty() ? null : values.getFirst(); }
    private int number(PluginHttpRequest request, String key, int fallback) { try { return Integer.parseInt(query(request, key)); } catch (Exception ignored) { return fallback; } }
}
