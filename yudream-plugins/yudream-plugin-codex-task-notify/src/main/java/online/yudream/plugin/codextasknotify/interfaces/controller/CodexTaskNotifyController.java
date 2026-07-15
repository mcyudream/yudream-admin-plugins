package online.yudream.plugin.codextasknotify.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.plugin.codextasknotify.bootstrap.CodexTaskNotifyPlugin;
import online.yudream.plugin.codextasknotify.interfaces.http.CodexTaskNotifyHttpFacade;

public class CodexTaskNotifyController {

    private final CodexTaskNotifyHttpFacade http;

    public CodexTaskNotifyController(CodexTaskNotifyHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "POST", path = "/notify", permission = CodexTaskNotifyPlugin.SEND_PERMISSION)
    public PluginHttpResponse notify(PluginHttpRequest request) {
        return http.notify(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/tasks/start", permission = CodexTaskNotifyPlugin.SEND_PERMISSION)
    public PluginHttpResponse start(PluginHttpRequest request) {
        return http.start(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/tasks/heartbeat", permission = CodexTaskNotifyPlugin.SEND_PERMISSION)
    public PluginHttpResponse heartbeat(PluginHttpRequest request) {
        return http.heartbeat(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/tasks/complete", permission = CodexTaskNotifyPlugin.SEND_PERMISSION)
    public PluginHttpResponse complete(PluginHttpRequest request) {
        return http.complete(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/tasks/action-required", permission = CodexTaskNotifyPlugin.SEND_PERMISSION)
    public PluginHttpResponse actionRequired(PluginHttpRequest request) {
        return http.actionRequired(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/tasks/interrupt", permission = CodexTaskNotifyPlugin.SEND_PERMISSION)
    public PluginHttpResponse interrupt(PluginHttpRequest request) {
        return http.interrupt(request);
    }
}
