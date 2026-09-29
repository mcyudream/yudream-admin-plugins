package online.yudream.base.plugin.skin.interfaces.http;

import online.yudream.base.plugin.skin.application.service.YuDreamSkinAppService;
import online.yudream.base.plugin.skin.bootstrap.YuDreamSkinPlugin;
import online.yudream.base.plugin.skin.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.skin.interfaces.assembler.YuDreamSkinWebAssembler;
import online.yudream.base.plugin.skin.interfaces.request.AssignTextureRequest;
import online.yudream.base.plugin.skin.interfaces.request.ClosetItemSaveRequest;
import online.yudream.base.plugin.skin.interfaces.request.CreatePlayerRequest;
import online.yudream.base.plugin.skin.interfaces.request.CreateSkinUserRequest;
import online.yudream.base.plugin.skin.interfaces.request.DefaultPlayerSaveRequest;
import online.yudream.base.plugin.skin.interfaces.request.MigrationRequest;
import online.yudream.base.plugin.skin.interfaces.request.RenameClosetItemRequest;
import online.yudream.base.plugin.skin.interfaces.request.RenamePlayerRequest;
import online.yudream.base.plugin.skin.interfaces.request.SkinSettingsSaveRequest;
import online.yudream.base.plugin.skin.interfaces.request.TextureUploadRequest;
import online.yudream.base.plugin.skin.interfaces.request.TextureUpdateRequest;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.skin.api.PluginSkinProfile;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;

import java.io.ByteArrayOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class YuDreamSkinHttpFacade {

    private final YuDreamSkinAppService appService;
    private final YuDreamSkinWebAssembler assembler = new YuDreamSkinWebAssembler();
    private final FrameworkServices framework;

    public YuDreamSkinHttpFacade(YuDreamSkinAppService appService, FrameworkServices framework) {
        this.appService = appService;
        this.framework = framework;
    }

    public PluginHttpResponse status() {
        return PluginHttpResponse.ok(appService.summary());
    }

    public PluginHttpResponse users(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.listUsers(page(request), size(request)));
    }

    public PluginHttpResponse me(PluginHttpRequest request) {
        Long hostUserId = request.principal().userId();
        String userId = ownerId(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("hostUser", hostUserId == null ? null : framework.users().findById(hostUserId).orElse(null));
        body.put("skinUser", appService.findUser(userId).orElse(null));
        body.put("defaultPlayerName", appService.defaultPlayer(userId).map(player -> player.name()).orElse(null));
        body.put("permissions", request.principal().permissions());
        return PluginHttpResponse.ok(body);
    }

    public PluginHttpResponse createUser(PluginHttpRequest request) {
        CreateSkinUserRequest body = JsonSupport.read(request.body(), CreateSkinUserRequest.class);
        return PluginHttpResponse.ok(appService.createUser(assembler.toCmd(body)));
    }

    public PluginHttpResponse players(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.listPlayers(page(request), size(request)).stream()
                .map(player -> assembler.toRes(player, this::ownerProfile))
                .toList());
    }

    public PluginHttpResponse myPlayers(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.listPlayersByOwner(ownerId(request), page(request), size(request)).stream()
                .map(player -> assembler.toRes(player, this::ownerProfile))
                .toList());
    }

    public PluginHttpResponse createPlayer(PluginHttpRequest request) {
        CreatePlayerRequest body = JsonSupport.read(request.body(), CreatePlayerRequest.class);
        return PluginHttpResponse.ok(appService.createPlayer(
                assembler.toCmd(body),
                request.principal().userId()
        ));
    }

    public PluginHttpResponse createMyPlayer(PluginHttpRequest request) {
        CreatePlayerRequest body = JsonSupport.read(request.body(), CreatePlayerRequest.class);
        return PluginHttpResponse.ok(appService.createPlayer(
                assembler.toCmd(body, ownerId(request)),
                request.principal().userId()
        ));
    }

    public PluginHttpResponse player(PluginHttpRequest request) {
        String name = lastPathSegment(request.path());
        return appService.findPlayer(name)
                .map(PluginHttpResponse::ok)
                .orElseGet(() -> PluginHttpResponse.rawJson(404, Map.of("message", "角色不存在")));
    }

    public PluginHttpResponse renamePlayer(PluginHttpRequest request) {
        String name = playerNameFromPath(request.path());
        RenamePlayerRequest body = JsonSupport.read(request.body(), RenamePlayerRequest.class);
        return PluginHttpResponse.ok(appService.renamePlayer(name, assembler.toCmd(body)));
    }

    public PluginHttpResponse renameMyPlayer(PluginHttpRequest request) {
        String name = playerNameFromPath(request.path());
        RenamePlayerRequest body = JsonSupport.read(request.body(), RenamePlayerRequest.class);
        return PluginHttpResponse.ok(appService.renameOwnPlayer(name, ownerId(request), assembler.toCmd(body)));
    }

    public PluginHttpResponse deletePlayer(PluginHttpRequest request) {
        appService.deletePlayer(lastPathSegment(request.path()));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    public PluginHttpResponse deleteMyPlayer(PluginHttpRequest request) {
        appService.deleteOwnPlayer(lastPathSegment(request.path()), ownerId(request));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    public PluginHttpResponse assignTextures(PluginHttpRequest request) {
        String name = playerNameFromPath(request.path());
        AssignTextureRequest body = JsonSupport.read(request.body(), AssignTextureRequest.class);
        return PluginHttpResponse.ok(appService.assignTextures(
                name,
                assembler.toCmd(body)
        ));
    }

    public PluginHttpResponse assignMyTextures(PluginHttpRequest request) {
        String name = playerNameFromPath(request.path());
        AssignTextureRequest body = JsonSupport.read(request.body(), AssignTextureRequest.class);
        return PluginHttpResponse.ok(appService.assignOwnTextures(
                name,
                ownerId(request),
                assembler.toCmd(body)
        ));
    }

    public PluginHttpResponse saveMyDefaultPlayer(PluginHttpRequest request) {
        DefaultPlayerSaveRequest body = JsonSupport.read(request.body(), DefaultPlayerSaveRequest.class);
        return PluginHttpResponse.ok(appService.setDefaultPlayer(ownerId(request), assembler.toCmd(body)));
    }

    public PluginHttpResponse textures(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.listVisibleTextures(
                ownerId(request),
                false,
                page(request),
                size(request)
        ));
    }

    public PluginHttpResponse adminTextures(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.listTextures(page(request), size(request)));
    }

    public PluginHttpResponse uploadTexture(PluginHttpRequest request) {
        TextureUploadRequest body = JsonSupport.read(request.body(), TextureUploadRequest.class);
        return PluginHttpResponse.ok(appService.uploadTexture(
                assembler.toCmd(body),
                request.principal().userId()
        ));
    }

    public PluginHttpResponse updateTexture(PluginHttpRequest request) {
        TextureUpdateRequest body = JsonSupport.read(request.body(), TextureUpdateRequest.class);
        return PluginHttpResponse.ok(appService.updateTexture(
                lastPathSegment(request.path()),
                assembler.toCmd(body)
        ));
    }

    public PluginHttpResponse deleteTexture(PluginHttpRequest request) {
        appService.deleteTexture(lastPathSegment(request.path()));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    public PluginHttpResponse uploadMyTexture(PluginHttpRequest request) {
        TextureUploadRequest body = JsonSupport.read(request.body(), TextureUploadRequest.class);
        return PluginHttpResponse.ok(appService.uploadOwnTexture(
                assembler.toCmd(body),
                ownerId(request),
                request.principal().userId()
        ));
    }

    public PluginHttpResponse updateMyTexture(PluginHttpRequest request) {
        TextureUpdateRequest body = JsonSupport.read(request.body(), TextureUpdateRequest.class);
        return PluginHttpResponse.ok(appService.updateOwnTexture(
                lastPathSegment(request.path()),
                ownerId(request),
                assembler.toCmd(body)
        ));
    }

    public PluginHttpResponse deleteMyTexture(PluginHttpRequest request) {
        appService.deleteOwnTexture(lastPathSegment(request.path()), ownerId(request));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    public PluginHttpResponse textureContent(PluginHttpRequest request) {
        String hash = lastPathSegment(request.path());
        return appService.readTexture(hash)
                .map(this::toBinaryResponse)
                .orElseGet(() -> PluginHttpResponse.rawJson(404, Map.of("message", "材质文件不存在")));
    }

    /**
     * 皮肤正面立绘渲染（公开，/textures/{hash}/render?height=）：
     * 按 Minecraft 官方布局把 64x64（或旧版 64x32）材质的正面区域拼装为全身像，
     * 邻近采样保持像素风；移动端等无 WebGL 的端直接以图片消费。
     */
    public PluginHttpResponse textureRender(PluginHttpRequest request) {
        String[] segments = request.path().trim().split("/");
        String hash = segments.length >= 2 ? decode(segments[segments.length - 2]) : "";
        int height = Math.min(Math.max(intQuery(request, "height", 320), 64), 1024);
        return appService.readTexture(hash)
                .map(file -> renderFrontView(file, height))
                .orElseGet(() -> PluginHttpResponse.rawJson(404, Map.of("message", "材质文件不存在")));
    }

    private PluginHttpResponse renderFrontView(PluginStoredFile file, int height) {
        BufferedImage skin;
        try (var inputStream = file.inputStream()) {
            skin = ImageIO.read(inputStream);
        } catch (IOException e) {
            return PluginHttpResponse.rawJson(500, Map.of("message", "材质读取失败：" + e.getMessage()));
        }
        if (skin == null) {
            return PluginHttpResponse.rawJson(400, Map.of("message", "不是有效的皮肤材质图片"));
        }
        return binaryPng(renderFrontView(skin, height));
    }

    /** 正面立绘拼装（包私有以便单测）：64x64 新版布局 / 64x32 旧版（左肢镜像右肢、仅帽层）。 */
    static BufferedImage renderFrontView(BufferedImage skin, int height) {
        boolean legacy = skin.getHeight() <= 32;
        int unit = Math.max(2, height / 32);
        BufferedImage out = new BufferedImage(16 * unit, 32 * unit, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = out.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        // 正面部件（源区域 → 画布格位）；旧版 64x32 无独立左肢/下半外层，左肢镜像右肢区域
        drawPart(graphics, skin, unit, 44, 20, 4, 12, 0, 8);
        drawPart(graphics, skin, unit, legacy ? 44 : 36, legacy ? 20 : 52, 4, 12, 12, 8);
        drawPart(graphics, skin, unit, 20, 20, 8, 12, 4, 8);
        drawPart(graphics, skin, unit, 4, 20, 4, 12, 4, 20);
        drawPart(graphics, skin, unit, legacy ? 4 : 20, legacy ? 20 : 52, 4, 12, 8, 20);
        drawPart(graphics, skin, unit, 8, 8, 8, 8, 4, 0);
        // 外层（帽层等）：旧版仅帽层
        drawPart(graphics, skin, unit, 40, 8, 8, 8, 4, 0);
        if (!legacy) {
            drawPart(graphics, skin, unit, 44, 36, 4, 12, 0, 8);
            drawPart(graphics, skin, unit, 20, 36, 8, 12, 4, 8);
            drawPart(graphics, skin, unit, 4, 36, 4, 12, 4, 20);
            drawPart(graphics, skin, unit, 4, 52, 4, 12, 8, 20);
            drawPart(graphics, skin, unit, 52, 52, 4, 12, 12, 8);
        }
        graphics.dispose();
        return out;
    }

    private static byte[] pngBytes(BufferedImage image) {
        try (var bos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PNG 编码失败：" + e.getMessage(), e);
        }
    }

    private PluginHttpResponse binaryPng(BufferedImage image) {
        return new PluginHttpResponse(
                200,
                Map.of("Cache-Control", "public, max-age=31536000"),
                "image/png",
                pngBytes(image),
                false
    );}

    /** 把材质的 (sx,sy,w,h) 区域邻近缩放画到画布 (dx,dy) 格位（格 = 渲染高的 1/32）。 */
    private static void drawPart(Graphics2D graphics, BufferedImage skin, int unit, int sx, int sy, int w, int h, int dx, int dy) {
        if (sy + h > skin.getHeight() || sx + w > skin.getWidth()) {
            return;
        }
        graphics.drawImage(skin, dx * unit, dy * unit, dx * unit + w * unit, dy * unit + h * unit,
                sx, sy, sx + w, sy + h, null);
    }



    public PluginHttpResponse customSkinProfile(PluginHttpRequest request) {
        String name = lastPathSegment(request.path());
        PluginSkinProfile profile = appService.findProfileByName(name.replace(".json", ""))
                .orElse(null);
        if (profile == null) {
            return PluginHttpResponse.rawJson(404, Map.of("message", "角色不存在"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", profile.name());
        Map<String, String> skins = new LinkedHashMap<>();
        if (profile.skin() != null) {
            skins.put(profile.skin().model() == null ? "default" : profile.skin().model(), profile.skin().hash());
        }
        body.put("skins", skins);
        body.put("cape", profile.cape() == null ? null : profile.cape().hash());
        return PluginHttpResponse.rawJson(200, body);
    }

    public PluginHttpResponse closet(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.listCloset(firstQuery(request, "userId"), page(request), size(request)));
    }

    public PluginHttpResponse myCloset(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.listClosetItemViews(ownerId(request), page(request), size(request)));
    }

    public PluginHttpResponse saveClosetItem(PluginHttpRequest request) {
        ClosetItemSaveRequest body = JsonSupport.read(request.body(), ClosetItemSaveRequest.class);
        return PluginHttpResponse.ok(appService.saveClosetItem(
                assembler.toCmd(body),
                request.principal().userId()
        ));
    }

    public PluginHttpResponse saveMyClosetItem(PluginHttpRequest request) {
        ClosetItemSaveRequest body = JsonSupport.read(request.body(), ClosetItemSaveRequest.class);
        return PluginHttpResponse.ok(appService.saveOwnClosetItem(
                assembler.toCmd(body, ownerId(request)),
                ownerId(request),
                request.principal().userId()
        ));
    }

    public PluginHttpResponse renameClosetItem(PluginHttpRequest request) {
        RenameClosetItemRequest body = JsonSupport.read(request.body(), RenameClosetItemRequest.class);
        return PluginHttpResponse.ok(appService.renameClosetItem(lastPathSegment(request.path()), assembler.toCmd(body)));
    }

    public PluginHttpResponse renameMyClosetItem(PluginHttpRequest request) {
        RenameClosetItemRequest body = JsonSupport.read(request.body(), RenameClosetItemRequest.class);
        return PluginHttpResponse.ok(appService.renameOwnClosetItem(
                lastPathSegment(request.path()),
                ownerId(request),
                assembler.toCmd(body)
        ));
    }

    public PluginHttpResponse deleteClosetItem(PluginHttpRequest request) {
        appService.deleteClosetItem(lastPathSegment(request.path()));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    public PluginHttpResponse deleteMyClosetItem(PluginHttpRequest request) {
        appService.deleteOwnClosetItem(lastPathSegment(request.path()), ownerId(request));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    public PluginHttpResponse settings() {
        return PluginHttpResponse.ok(appService.settings());
    }

    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        SkinSettingsSaveRequest body = JsonSupport.read(request.body(), SkinSettingsSaveRequest.class);
        return PluginHttpResponse.ok(appService.saveSettings(assembler.toCmd(body)));
    }

    public PluginHttpResponse migrate(PluginHttpRequest request) {
        MigrationRequest body = JsonSupport.read(request.body(), MigrationRequest.class);
        return PluginHttpResponse.json(202, appService.startMigration(assembler.toCmd(body)));
    }

    public PluginHttpResponse migrationStatus() {
        return PluginHttpResponse.ok(appService.migrationStatus());
    }

    public PluginHttpResponse migrationEvents() {
        return new PluginHttpResponse(
                200,
                Map.of("Cache-Control", "no-cache"),
                "text/event-stream",
                appService.migrationEvents(),
                false
        );
    }

    private PluginHttpResponse toBinaryResponse(PluginStoredFile file) {
        try {
            return new PluginHttpResponse(
                    200,
                    Map.of("Cache-Control", "public, max-age=31536000"),
                    "image/png",
                    file.inputStream().readAllBytes(),
                    false
            );
        } catch (IOException e) {
            return PluginHttpResponse.rawJson(500, Map.of("message", "材质读取失败：" + e.getMessage()));
        }
    }

    private int page(PluginHttpRequest request) {
        return intQuery(request, "page", 1);
    }

    private int size(PluginHttpRequest request) {
        return intQuery(request, "size", 20);
    }

    private int intQuery(PluginHttpRequest request, String key, int defaultValue) {
        List<String> values = request.query().get(key);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        return Integer.parseInt(values.get(0));
    }

    private String firstQuery(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private String lastPathSegment(String path) {
        String[] segments = trim(path).split("/");
        return decode(segments[segments.length - 1]);
    }

    private String playerNameFromPath(String path) {
        String[] segments = trim(path).split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if ("players".equals(segments[i])) {
                return decode(segments[i + 1]);
            }
        }
        return lastPathSegment(path);
    }

    private String ownerId(PluginHttpRequest request) {
        Long userId = request.principal().userId();
        return userId == null ? "system" : String.valueOf(userId);
    }

    private PluginUserProfile ownerProfile(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || "system".equalsIgnoreCase(ownerId)) {
            return null;
        }
        try {
            return framework.users().findById(Long.parseLong(ownerId)).orElse(null);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String pathSegment(String path, int indexAfterRoot) {
        String[] segments = trim(path).split("/");
        if (indexAfterRoot < 0 || indexAfterRoot >= segments.length) {
            return "";
        }
        return decode(segments[indexAfterRoot]);
    }

    private String trim(String path) {
        String value = path == null ? "" : path;
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
