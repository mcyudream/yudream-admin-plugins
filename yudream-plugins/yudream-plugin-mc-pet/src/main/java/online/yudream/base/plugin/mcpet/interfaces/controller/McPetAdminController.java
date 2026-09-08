package online.yudream.base.plugin.mcpet.interfaces.controller;

import online.yudream.base.plugin.mcpet.bootstrap.McPetPlugin;
import online.yudream.base.plugin.mcpet.interfaces.http.McPetHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class McPetAdminController {

    private final McPetHttpFacade http;

    public McPetAdminController(McPetHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/defaults", permission = McPetPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse defaults() {
        return http.defaults();
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/defaults", permission = McPetPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveDefaults(PluginHttpRequest request) {
        return http.saveDefaults(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/pets", permission = McPetPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse pets(PluginHttpRequest request) {
        return http.adminPets(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/pets/{userId}", permission = McPetPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse petDetail(PluginHttpRequest request) {
        return http.adminPetDetail(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/pets/{userId}/reset", permission = McPetPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse resetPet(PluginHttpRequest request) {
        return http.adminResetPet(request);
    }
}
