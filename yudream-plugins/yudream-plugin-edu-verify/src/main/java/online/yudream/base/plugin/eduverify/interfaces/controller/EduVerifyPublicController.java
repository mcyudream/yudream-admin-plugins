package online.yudream.base.plugin.eduverify.interfaces.controller;

import online.yudream.base.plugin.eduverify.interfaces.http.EduVerifyHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class EduVerifyPublicController {

    private final EduVerifyHttpFacade http;

    public EduVerifyPublicController(EduVerifyHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/public/methods")
    public PluginHttpResponse methods() {
        return http.methods();
    }

    @PluginHttpEndpoint(method = "GET", path = "/public/status")
    public PluginHttpResponse status(PluginHttpRequest request) {
        return http.publicStatus(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/public/email/send-code")
    public PluginHttpResponse sendEmailCode(PluginHttpRequest request) {
        return http.sendEmailCode(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/public/email/verify")
    public PluginHttpResponse verifyEmailCode(PluginHttpRequest request) {
        return http.verifyEmailCode(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/public/chsi/verify")
    public PluginHttpResponse chsiVerify(PluginHttpRequest request) {
        return http.chsiVerify(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/public/chsi/confirm")
    public PluginHttpResponse chsiConfirm(PluginHttpRequest request) {
        return http.chsiConfirm(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/public/manual/upload")
    public PluginHttpResponse manualUpload(PluginHttpRequest request) {
        return http.publicManualUpload(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/public/manual/submit")
    public PluginHttpResponse manualSubmit(PluginHttpRequest request) {
        return http.publicManualSubmit(request);
    }
}
