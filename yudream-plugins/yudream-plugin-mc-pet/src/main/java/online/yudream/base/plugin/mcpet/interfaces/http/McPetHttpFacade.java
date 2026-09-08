package online.yudream.base.plugin.mcpet.interfaces.http;

import online.yudream.base.plugin.mcpet.application.cmd.PetDefaultsSaveCmd;
import online.yudream.base.plugin.mcpet.application.cmd.PetPreferenceSaveCmd;
import online.yudream.base.plugin.mcpet.application.service.McPetAppService;
import online.yudream.base.plugin.mcpet.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.mcpet.interfaces.request.PetDefaultsSaveRequest;
import online.yudream.base.plugin.mcpet.interfaces.request.PetPreferenceSaveRequest;
import online.yudream.base.plugin.mcpet.interfaces.request.PetSkinUploadRequest;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.List;
import java.util.Map;

public class McPetHttpFacade {

    private final McPetAppService appService;

    public McPetHttpFacade(McPetAppService appService) {
        this.appService = appService;
    }

    public PluginHttpResponse myPet(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.myPet(currentUserId(request)));
    }

    public PluginHttpResponse saveMyPet(PluginHttpRequest request) {
        PetPreferenceSaveRequest body = JsonSupport.read(request.body(), PetPreferenceSaveRequest.class);
        PetPreferenceSaveCmd cmd = new PetPreferenceSaveCmd(
                body.mode(), body.playerName(), body.closetItemId(),
                body.size(), body.corner(), body.positionX(), body.positionY(), body.hidden(), body.clearPosition());
        return PluginHttpResponse.ok(appService.saveMyPreference(currentUserId(request), cmd));
    }

    public PluginHttpResponse myOptions(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.myOptions(currentUserId(request)));
    }

    public PluginHttpResponse uploadMySkin(PluginHttpRequest request) {
        PetSkinUploadRequest body = JsonSupport.read(request.body(), PetSkinUploadRequest.class);
        return PluginHttpResponse.ok(appService.uploadMySkin(
                currentUserId(request), body.name(), body.model(), body.base64()));
    }

    public PluginHttpResponse defaults() {
        return PluginHttpResponse.ok(appService.defaults());
    }

    public PluginHttpResponse saveDefaults(PluginHttpRequest request) {
        PetDefaultsSaveRequest body = JsonSupport.read(request.body(), PetDefaultsSaveRequest.class);
        PetDefaultsSaveCmd cmd = new PetDefaultsSaveCmd(
                body.mode(), body.playerName(), body.textureHash(), body.model(),
                body.animation(), body.clickAction(), body.size(), body.corner());
        return PluginHttpResponse.ok(appService.saveDefaults(cmd));
    }

    public PluginHttpResponse adminPets(PluginHttpRequest request) {
        int page = intQuery(request, "page", 1);
        int size = intQuery(request, "size", 10);
        return PluginHttpResponse.ok(Map.of(
                "records", appService.adminList(page, size),
                "total", appService.preferenceCount()));
    }

    public PluginHttpResponse adminPetDetail(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.adminDetail(pathSegment(request.path(), 2)));
    }

    public PluginHttpResponse adminResetPet(PluginHttpRequest request) {
        String userId = pathSegment(request.path(), 2);
        appService.resetPreference(userId);
        return PluginHttpResponse.ok(appService.adminDetail(userId));
    }

    private String currentUserId(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return String.valueOf(request.principal().userId());
    }

    private int intQuery(PluginHttpRequest request, String key, int defaultValue) {
        List<String> values = request.query() == null ? null : request.query().get(key);
        if (values == null || values.isEmpty() || values.get(0).isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(values.get(0).trim()));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String pathSegment(String path, int index) {
        if (path == null) {
            throw new IllegalArgumentException("路径参数缺失");
        }
        String[] segments = path.split("/");
        int position = 0;
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if (position == index) {
                return segment;
            }
            position++;
        }
        throw new IllegalArgumentException("路径参数缺失");
    }
}
