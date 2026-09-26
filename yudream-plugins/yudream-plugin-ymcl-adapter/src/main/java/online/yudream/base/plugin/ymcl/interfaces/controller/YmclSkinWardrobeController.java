package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.Map;

/**
 * YAP §6.11 皮肤衣柜（skins 能力）：把 yudream-skin 插件的 SPI
 * （PluginSkinService）聚合为启动器域衣柜端点。皮肤站未安装/未启用
 * 时端点统一降级 501，capabilities 也不会宣告 skins，启动器自动回退只读态。
 *
 * 本类签名里绝不允许出现 skin.api 类型：宿主注册期 getDeclaredMethods 会解析
 * 全部方法签名，皮肤站缺席的宿主会在这里 NCDFE 炸掉整个 onEnable。skin 类型
 * 全部隔离在 {@link YmclSkinWardrobeHandlers}（按需加载），此处只做边界
 * LinkageError → 501 的转译，也不再需要把 skin/api shade 进本 JAR。
 */
public class YmclSkinWardrobeController {

    private final PluginContext context;
    /** 复用 capabilities 控制器的 origin 自报逻辑（APP_WEB_URL 优先、代理头回退）。 */
    private final YmclCapabilitiesController capabilities;

    public YmclSkinWardrobeController(PluginContext context, YmclCapabilitiesController capabilities) {
        this.context = context;
        this.capabilities = capabilities;
    }

    // GET /v1/skins/profiles — 当前域用户名下的 Minecraft 档案
    @PluginHttpEndpoint(method = "GET", path = "/v1/skins/profiles",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse profiles(PluginHttpRequest request) {
        try {
            return YmclSkinWardrobeHandlers.profiles(context, request);
        } catch (LinkageError error) {
            return unavailable();
        }
    }

    // POST /v1/skins/profiles — 创建当前用户的 Minecraft 角色
    @PluginHttpEndpoint(method = "POST", path = "/v1/skins/profiles",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse createProfile(PluginHttpRequest request) {
        try {
            return YmclSkinWardrobeHandlers.createProfile(context, request);
        } catch (LinkageError error) {
            return unavailable();
        }
    }

    // GET /v1/skins/closet/{profileId} — 档案衣柜
    @PluginHttpEndpoint(method = "GET", path = "/v1/skins/closet/{profileId}",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse closet(PluginHttpRequest request) {
        try {
            return YmclSkinWardrobeHandlers.closet(context, request, capabilities);
        } catch (LinkageError error) {
            return unavailable();
        }
    }

    // POST /v1/skins/closet/{profileId}/equip — 一键换装（null = 卸下）
    @PluginHttpEndpoint(method = "POST", path = "/v1/skins/closet/{profileId}/equip",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse equip(PluginHttpRequest request) {
        try {
            return YmclSkinWardrobeHandlers.equip(context, request, capabilities);
        } catch (LinkageError error) {
            return unavailable();
        }
    }

    // POST /v1/skins/closet/{profileId}/skins — 上传皮肤入库（不自动装备）
    @PluginHttpEndpoint(method = "POST", path = "/v1/skins/closet/{profileId}/skins",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse upload(PluginHttpRequest request) {
        try {
            return YmclSkinWardrobeHandlers.upload(context, request, capabilities);
        } catch (LinkageError error) {
            return unavailable();
        }
    }

    // POST /v1/skins/closet/{profileId}/capes — 上传披风入库（不自动装备）
    @PluginHttpEndpoint(method = "POST", path = "/v1/skins/closet/{profileId}/capes",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse uploadCape(PluginHttpRequest request) {
        try {
            return YmclSkinWardrobeHandlers.uploadCape(context, request, capabilities);
        } catch (LinkageError error) {
            return unavailable();
        }
    }

    // GET /v1/skins/library?page&limit — 公共皮肤库（公开 + 自有纹理）
    @PluginHttpEndpoint(method = "GET", path = "/v1/skins/library",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse library(PluginHttpRequest request) {
        try {
            return YmclSkinWardrobeHandlers.library(context, request, capabilities);
        } catch (LinkageError error) {
            return unavailable();
        }
    }

    // POST /v1/skins/closet/{profileId}/collect — 公共皮肤收入衣柜
    @PluginHttpEndpoint(method = "POST", path = "/v1/skins/closet/{profileId}/collect",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse collect(PluginHttpRequest request) {
        try {
            return YmclSkinWardrobeHandlers.collect(context, request, capabilities);
        } catch (LinkageError error) {
            return unavailable();
        }
    }

    // DELETE /v1/skins/closet/{profileId}/skins/{skinId} — 删除衣柜皮肤
    @PluginHttpEndpoint(method = "DELETE", path = "/v1/skins/closet/{profileId}/skins/{skinId}",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        try {
            return YmclSkinWardrobeHandlers.delete(context, request);
        } catch (LinkageError error) {
            return unavailable();
        }
    }

    private static PluginHttpResponse unavailable() {
        return PluginHttpResponse.rawJson(501, Map.of(
                "error", "skins_unavailable",
                "message", "该域未启用皮肤站服务"));
    }
}
