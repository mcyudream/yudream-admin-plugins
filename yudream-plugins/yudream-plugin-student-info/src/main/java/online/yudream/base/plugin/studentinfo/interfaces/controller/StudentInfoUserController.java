package online.yudream.base.plugin.studentinfo.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.studentinfo.bootstrap.StudentInfoPlugin;
import online.yudream.base.plugin.studentinfo.interfaces.http.StudentInfoHttpFacade;

public class StudentInfoUserController {
    private final StudentInfoHttpFacade http;

    public StudentInfoUserController(StudentInfoHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me", permission = StudentInfoPlugin.USER_PERMISSION)
    public PluginHttpResponse mine(PluginHttpRequest request) {
        return http.mine(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me", permission = StudentInfoPlugin.USER_PERMISSION)
    public PluginHttpResponse saveMine(PluginHttpRequest request) {
        return http.saveMine(request);
    }
}
