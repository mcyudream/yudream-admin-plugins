package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;
import online.yudream.base.plugin.ymcl.interfaces.support.PathSegments;
import online.yudream.base.plugin.skin.api.PluginSkinClosetItem;
import online.yudream.base.plugin.skin.api.PluginSkinProfile;
import online.yudream.base.plugin.skin.api.PluginSkinService;
import online.yudream.base.plugin.skin.api.PluginSkinTexture;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * YAP §6.11 皮肤衣柜（skins 能力）：把 yudream-skin 插件的 SPI
 * （{@link PluginSkinService}）聚合为启动器域衣柜端点。皮肤站未安装/未启用
 * 时端点统一降级 501，capabilities 也不会宣告 skins，启动器自动回退只读态。
 *
 * 字段名与启动器侧 serde 结构（YmclCloset 等）逐字一致（snake_case）。
 */
public class YmclSkinWardrobeController {

    private static final String SKIN_PLUGIN_CODE = "yudream-skin";
    /** 皮肤站匿名纹理直出端点（与 authlib-injector 的 textureBaseUrl 一致）。 */
    private static final String TEXTURE_BASE_PATH = "/api/plugins/yudream-skin/textures/";

    private final PluginContext context;
    /** 复用 capabilities 控制器的 origin 自报逻辑（APP_WEB_URL 优先、代理头回退）。 */
    private final YmclCapabilitiesController capabilities;

    public YmclSkinWardrobeController(PluginContext context, YmclCapabilitiesController capabilities) {
        this.context = context;
        this.capabilities = capabilities;
    }

    // ------------------------------------------------------------------
    // GET /v1/skins/profiles — 当前域用户名下的 Minecraft 档案
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "GET", path = "/v1/skins/profiles",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse profiles(PluginHttpRequest request) {
        Optional<PluginSkinService> skin = skinService();
        if (skin.isEmpty()) {
            return unavailable();
        }
        Long userId = principalUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        List<PluginSkinProfile> profiles = skin.get().findProfilesByOwner(String.valueOf(userId));
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int index = 0; index < profiles.size(); index++) {
            PluginSkinProfile profile = profiles.get(index);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", profile.uuid());
            node.put("name", profile.name());
            // SPI 不知道当前会话绑定哪个档案；单档案直接标 current，多档案由
            // 启动器让用户选择。
            node.put("current", index == 0);
            nodes.add(node);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profiles", nodes);
        return PluginHttpResponse.rawJson(200, body);
    }

    // ------------------------------------------------------------------
    // POST /v1/skins/profiles — 创建当前用户的 Minecraft 角色
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "POST", path = "/v1/skins/profiles",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse createProfile(PluginHttpRequest request) {
        Optional<PluginSkinService> skin = skinService();
        if (skin.isEmpty()) {
            return unavailable();
        }
        Long userId = principalUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        Map<String, Object> body;
        try {
            body = readJson(request.body());
        } catch (Exception error) {
            return badRequest(400, "invalid_body", "Request body must be JSON");
        }
        String name = textOrNull(body.get("name"));
        if (name == null || name.isBlank()) {
            return badRequest(400, "invalid_body", "name is required");
        }
        PluginSkinProfile profile;
        try {
            profile = skin.get().createPlayerForOwner(String.valueOf(userId), name.trim());
        } catch (IllegalArgumentException error) {
            return badRequest(400, "invalid_name", String.valueOf(error.getMessage()));
        }
        Map<String, Object> node = profileNode(profile);
        node.put("current", true);
        return PluginHttpResponse.rawJson(200, Map.of("profile", node));
    }

    // ------------------------------------------------------------------
    // GET /v1/skins/closet/{profileId} — 档案衣柜
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "GET", path = "/v1/skins/closet/{profileId}",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse closet(PluginHttpRequest request) {
        Optional<PluginSkinService> skin = skinService();
        if (skin.isEmpty()) {
            return unavailable();
        }
        Long userId = principalUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        String owner = String.valueOf(userId);
        PluginSkinProfile profile = ownedProfile(skin.get(), owner, PathSegments.segment(request.path(), 3));
        if (profile == null) {
            return forbidden();
        }
        String origin = origin(request);
        List<PluginSkinClosetItem> items = skin.get().findClosetByOwner(owner);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profile", profileNode(profile));
        body.put("equipped", equippedNode(origin, items, profile));
        body.put("skins", closetNodes(origin, skin.get(), items, false));
        body.put("capes", closetNodes(origin, skin.get(), items, true));
        return PluginHttpResponse.rawJson(200, body);
    }

    // ------------------------------------------------------------------
    // POST /v1/skins/closet/{profileId}/equip — 一键换装（null = 卸下）
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "POST", path = "/v1/skins/closet/{profileId}/equip",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse equip(PluginHttpRequest request) {
        Optional<PluginSkinService> skin = skinService();
        if (skin.isEmpty()) {
            return unavailable();
        }
        Long userId = principalUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        String owner = String.valueOf(userId);
        PluginSkinProfile profile = ownedProfile(skin.get(), owner, PathSegments.segment(request.path(), 3));
        if (profile == null) {
            return forbidden();
        }
        Map<String, Object> body;
        try {
            body = readJson(request.body());
        } catch (Exception error) {
            return badRequest(400, "invalid_body", "Request body must be JSON");
        }
        String skinId = textOrNull(body.get("skin_id"));
        String capeId = textOrNull(body.get("cape_id"));
        List<PluginSkinClosetItem> items = skin.get().findClosetByOwner(owner);
        String skinHash = resolveItemHash(items, skinId);
        String capeHash = resolveItemHash(items, capeId);
        if (skinId != null && skinHash == null) {
            return badRequest(400, "unknown_skin", "skin_id is not in this wardrobe");
        }
        if (capeId != null && capeHash == null) {
            return badRequest(400, "unknown_cape", "cape_id is not in this wardrobe");
        }
        try {
            if (skinHash != null) {
                skin.get().setProfileTexture(profile.uuid(), "skin", skinHash);
            } else {
                skin.get().clearProfileTexture(profile.uuid(), "skin");
            }
            if (capeHash != null) {
                skin.get().setProfileTexture(profile.uuid(), "cape", capeHash);
            } else {
                skin.get().clearProfileTexture(profile.uuid(), "cape");
            }
        } catch (IllegalArgumentException error) {
            return badRequest(400, "invalid_request", String.valueOf(error.getMessage()));
        }
        PluginSkinProfile updated = skin.get().findProfileByUuid(profile.uuid()).orElse(profile);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("equipped", equippedNode(origin(request), skin.get().findClosetByOwner(owner), updated));
        return PluginHttpResponse.rawJson(200, payload);
    }

    // ------------------------------------------------------------------
    // POST /v1/skins/closet/{profileId}/skins — 上传皮肤入库（不自动装备）
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "POST", path = "/v1/skins/closet/{profileId}/skins",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse upload(PluginHttpRequest request) {
        Optional<PluginSkinService> skin = skinService();
        if (skin.isEmpty()) {
            return unavailable();
        }
        Long userId = principalUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        Map<String, Object> body;
        try {
            body = readJson(request.body());
        } catch (Exception error) {
            return badRequest(400, "invalid_body", "Request body must be JSON");
        }
        String data = textOrNull(body.get("data"));
        if (data == null || data.isBlank()) {
            return badRequest(400, "invalid_body", "data (base64 PNG) is required");
        }
        String name = textOrNull(body.get("name"));
        // 启动器发 "classic"|"slim"，皮肤站 SkinTextureType.from 两者都认。
        String model = "slim".equalsIgnoreCase(textOrNull(body.get("model"))) ? "slim" : "classic";
        PluginSkinClosetItem item;
        try {
            item = skin.get().uploadClosetSkin(String.valueOf(userId), name, model, data);
        } catch (IllegalArgumentException error) {
            return badRequest(400, "invalid_skin", String.valueOf(error.getMessage()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("skin", skinNode(origin(request), item,
                skin.get().findTextureByHash(item.textureHash()).orElse(null)));
        return PluginHttpResponse.rawJson(200, payload);
    }

    // ------------------------------------------------------------------
    // POST /v1/skins/closet/{profileId}/capes — 上传披风入库（不自动装备）
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "POST", path = "/v1/skins/closet/{profileId}/capes",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse uploadCape(PluginHttpRequest request) {
        Optional<PluginSkinService> skin = skinService();
        if (skin.isEmpty()) {
            return unavailable();
        }
        Long userId = principalUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        Map<String, Object> body;
        try {
            body = readJson(request.body());
        } catch (Exception error) {
            return badRequest(400, "invalid_body", "Request body must be JSON");
        }
        String data = textOrNull(body.get("data"));
        if (data == null || data.isBlank()) {
            return badRequest(400, "invalid_body", "data (base64 PNG) is required");
        }
        String name = textOrNull(body.get("name"));
        PluginSkinClosetItem item;
        try {
            item = skin.get().uploadClosetCape(String.valueOf(userId), name, data);
        } catch (IllegalArgumentException error) {
            return badRequest(400, "invalid_cape", String.valueOf(error.getMessage()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cape", skinNode(origin(request), item,
                skin.get().findTextureByHash(item.textureHash()).orElse(null)));
        return PluginHttpResponse.rawJson(200, payload);
    }

    // ------------------------------------------------------------------
    // GET /v1/skins/library?page&limit — 公共皮肤库（公开 + 自有纹理）
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "GET", path = "/v1/skins/library",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse library(PluginHttpRequest request) {
        Optional<PluginSkinService> skin = skinService();
        if (skin.isEmpty()) {
            return unavailable();
        }
        Long userId = principalUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        int page = positiveIntOrDefault(request.query().get("page"), 1);
        int limit = Math.min(positiveIntOrDefault(request.query().get("limit"), 24), 48);
        List<PluginSkinTexture> textures =
                skin.get().findVisibleTextures(String.valueOf(userId), page, limit);
        String origin = origin(request);
        List<Map<String, Object>> items = new ArrayList<>();
        for (PluginSkinTexture texture : textures) {
            Map<String, Object> node = new LinkedHashMap<>();
            String type = "cape".equalsIgnoreCase(textOrNull(texture.type())) ? "cape" : "skin";
            node.put("id", texture.hash());
            node.put("hash", texture.hash());
            node.put("name", texture.name());
            node.put("type", type);
            node.put("variant", variantOf(texture.model()));
            node.put("url", origin + TEXTURE_BASE_PATH + texture.hash());
            items.add(node);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("page", page);
        // 满页即视为可能还有下一页（多取一条的代价大于偶尔多一次请求）。
        body.put("has_more", textures.size() >= limit);
        body.put("items", items);
        return PluginHttpResponse.rawJson(200, body);
    }

    // ------------------------------------------------------------------
    // POST /v1/skins/closet/{profileId}/collect — 公共皮肤收入衣柜
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "POST", path = "/v1/skins/closet/{profileId}/collect",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse collect(PluginHttpRequest request) {
        Optional<PluginSkinService> skin = skinService();
        if (skin.isEmpty()) {
            return unavailable();
        }
        Long userId = principalUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        Map<String, Object> body;
        try {
            body = readJson(request.body());
        } catch (Exception error) {
            return badRequest(400, "invalid_body", "Request body must be JSON");
        }
        String hash = textOrNull(body.get("hash"));
        if (hash == null || hash.isBlank()) {
            return badRequest(400, "invalid_body", "hash is required");
        }
        PluginSkinClosetItem item;
        try {
            item = skin.get().collectTextureToCloset(
                    String.valueOf(userId), hash, textOrNull(body.get("name")));
        } catch (IllegalArgumentException error) {
            return badRequest(400, "invalid_texture", String.valueOf(error.getMessage()));
        }
        String origin = origin(request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("skin", skinNode(origin, item,
                skin.get().findTextureByHash(item.textureHash()).orElse(null)));
        return PluginHttpResponse.rawJson(200, payload);
    }

    // ------------------------------------------------------------------
    // DELETE /v1/skins/closet/{profileId}/skins/{skinId} — 删除衣柜皮肤
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "DELETE", path = "/v1/skins/closet/{profileId}/skins/{skinId}",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        Optional<PluginSkinService> skin = skinService();
        if (skin.isEmpty()) {
            return unavailable();
        }
        Long userId = principalUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        String skinId = PathSegments.segment(request.path(), 5);
        if (skinId == null || skinId.isBlank()) {
            return badRequest(400, "invalid_path", "skinId is required");
        }
        try {
            skin.get().removeClosetSkin(String.valueOf(userId), skinId);
        } catch (IllegalArgumentException error) {
            // 衣柜项不存在 / 不是自己的衣柜 —— 对启动器同为不可操作。
            return badRequest(403, "forbidden", String.valueOf(error.getMessage()));
        }
        return PluginHttpResponse.rawJson(200, Map.of());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** 皮肤站插件可能未安装/未启用（LinkageError = 类不可达），一律降级。 */
    private Optional<PluginSkinService> skinService() {
        try {
            return context.service(SKIN_PLUGIN_CODE, PluginSkinService.class);
        } catch (LinkageError error) {
            return Optional.empty();
        }
    }

    private static PluginHttpResponse unavailable() {
        return badRequest(501, "skins_unavailable", "该域未启用皮肤站服务");
    }

    private static Long principalUserId(PluginHttpRequest request) {
        var principal = request.principal();
        return principal == null ? null : principal.userId();
    }

    private static PluginHttpResponse unauthorized() {
        return badRequest(401, "unauthenticated", "Sign in to this domain to use the wardrobe");
    }

    private static PluginHttpResponse forbidden() {
        return badRequest(403, "forbidden", "不是该档案的拥有者");
    }

    private static PluginHttpResponse badRequest(int status, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", code);
        payload.put("message", message);
        return PluginHttpResponse.rawJson(status, payload);
    }

    private static Map<String, Object> readJson(String body) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }

    private static String textOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static int positiveIntOrDefault(List<String> values, int fallback) {
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(values.get(0).trim());
            return Math.max(value, 1);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    /** 纹理等绝对 URL 的基址：与 capabilities 同源（APP_WEB_URL 优先）。 */
    private String origin(PluginHttpRequest request) {
        return capabilities.resolveEffectiveOrigin(request);
    }

    private static PluginSkinProfile ownedProfile(PluginSkinService skin, String owner, String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return null;
        }
        return skin.findProfileByUuid(profileId)
                .filter(profile -> owner.equals(profile.ownerId()))
                .orElse(null);
    }

    /** 衣柜条目 id（userId:hash）→ 纹理 hash；null 透传（表示卸下）。 */
    private static String resolveItemHash(List<PluginSkinClosetItem> items, String itemId) {
        if (itemId == null) {
            return null;
        }
        return items.stream()
                .filter(item -> itemId.equals(item.id()))
                .map(PluginSkinClosetItem::textureHash)
                .findFirst()
                .orElse(null);
    }

    /** 已装备纹理反查衣柜条目 id；不在衣柜（历史外置纹理）时给合成 id。 */
    private static String itemIdForHash(List<PluginSkinClosetItem> items, String hash) {
        return items.stream()
                .filter(item -> hash != null && hash.equals(item.textureHash()))
                .map(PluginSkinClosetItem::id)
                .findFirst()
                .orElse("hash:" + hash);
    }

    private static Map<String, Object> profileNode(PluginSkinProfile profile) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", profile.uuid());
        node.put("name", profile.name());
        return node;
    }

    private static Map<String, Object> equippedNode(String origin, List<PluginSkinClosetItem> items,
            PluginSkinProfile profile) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("skin", profile.skin() == null ? null : skinNode(origin,
                items.stream().filter(item -> item.textureHash().equals(profile.skin().hash())).findFirst()
                        .orElse(null),
                profile.skin()));
        node.put("cape_id", profile.cape() == null ? null : itemIdForHash(items, profile.cape().hash()));
        return node;
    }

    /** 衣柜按 skin/cape 分流：逐条查纹理 type（"skin" | "cape"）。 */
    private static List<Map<String, Object>> closetNodes(String origin, PluginSkinService skin,
            List<PluginSkinClosetItem> items, boolean capes) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (PluginSkinClosetItem item : items) {
            PluginSkinTexture texture = skin.findTextureByHash(item.textureHash()).orElse(null);
            boolean isCape = texture != null && "cape".equalsIgnoreCase(textOrNull(texture.type()));
            if (isCape != capes) {
                continue;
            }
            nodes.add(skinNode(origin, item, texture));
        }
        return nodes;
    }

    private static Map<String, Object> skinNode(String origin, PluginSkinClosetItem item,
            PluginSkinTexture texture) {
        String hash = texture != null ? texture.hash()
                : (item != null ? item.textureHash() : null);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", item != null ? item.id() : "hash:" + hash);
        node.put("name", item != null && item.itemName() != null ? item.itemName()
                : (texture != null ? texture.name() : null));
        node.put("variant", texture == null ? "UNKNOWN" : variantOf(texture.model()));
        node.put("url", origin + TEXTURE_BASE_PATH + hash);
        node.put("hash", hash);
        return node;
    }

    /** 皮肤站 model 值（default/slim/alex…）→ YAP variant（CLASSIC/SLIM/UNKNOWN）。 */
    private static String variantOf(String model) {
        if (model == null) {
            return "UNKNOWN";
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        if ("slim".equals(normalized) || "alex".equals(normalized)) {
            return "SLIM";
        }
        if ("default".equals(normalized) || "classic".equals(normalized) || "steve".equals(normalized)) {
            return "CLASSIC";
        }
        return "UNKNOWN";
    }
}
