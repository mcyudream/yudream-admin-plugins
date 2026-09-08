package online.yudream.base.plugin.mcpet.interfaces.controller;

import online.yudream.base.plugin.mcpet.bootstrap.McPetPlugin;
import online.yudream.base.plugin.mcpet.interfaces.http.McPetHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class McPetUserController {

    private final McPetHttpFacade http;

    public McPetUserController(McPetHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/pet", permission = McPetPlugin.USER_PERMISSION)
    public PluginHttpResponse myPet(PluginHttpRequest request) {
        return http.myPet(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/pet", permission = McPetPlugin.USER_PERMISSION)
    public PluginHttpResponse saveMyPet(PluginHttpRequest request) {
        return http.saveMyPet(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/pet/options", permission = McPetPlugin.USER_PERMISSION)
    public PluginHttpResponse myOptions(PluginHttpRequest request) {
        return http.myOptions(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/pet/skin", permission = McPetPlugin.USER_PERMISSION)
    public PluginHttpResponse uploadMySkin(PluginHttpRequest request) {
        return http.uploadMySkin(request);
    }
}
