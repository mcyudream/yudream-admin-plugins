package online.yudream.plugin.qqbotautomation.interfaces.http;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;
import online.yudream.plugin.qqbotautomation.application.service.AutomationPolicyService;
import online.yudream.plugin.qqbotautomation.application.service.MediaJobService;
import online.yudream.plugin.qqbotautomation.interfaces.support.JsonSupport;

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
    public PluginHttpResponse connections() { return PluginHttpResponse.ok(framework.messaging().connections()); }
    public PluginHttpResponse groups(PluginHttpRequest request) { return PluginHttpResponse.ok(framework.messaging().groups(query(request, "connectionId"))); }
    public PluginHttpResponse mediaJobs(PluginHttpRequest request) { return PluginHttpResponse.ok(Map.of("records", mediaJobs.page(number(request, "page", 1), number(request, "size", 10)), "total", mediaJobs.total())); }
    private String query(PluginHttpRequest request, String key) { var values = request.query().get(key); return values == null || values.isEmpty() ? null : values.getFirst(); }
    private int number(PluginHttpRequest request, String key, int fallback) { try { return Integer.parseInt(query(request, key)); } catch (Exception ignored) { return fallback; } }
}
