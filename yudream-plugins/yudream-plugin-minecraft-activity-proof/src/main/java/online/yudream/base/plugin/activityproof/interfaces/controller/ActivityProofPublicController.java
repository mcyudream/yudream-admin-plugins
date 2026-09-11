package online.yudream.base.plugin.activityproof.interfaces.controller;

import online.yudream.base.plugin.activityproof.interfaces.http.ActivityProofHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 公开站活动详情：匿名可达，只返回面向全体部门且已发布/已结束的活动。
 */
public class ActivityProofPublicController {

    private final ActivityProofHttpFacade http;

    public ActivityProofPublicController(ActivityProofHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/public/activities/{id}")
    public PluginHttpResponse publicActivity(PluginHttpRequest request) {
        return http.publicActivity(request);
    }
}
