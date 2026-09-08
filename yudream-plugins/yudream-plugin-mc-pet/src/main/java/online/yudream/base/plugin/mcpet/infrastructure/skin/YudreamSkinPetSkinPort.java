package online.yudream.base.plugin.mcpet.infrastructure.skin;

import online.yudream.base.plugin.mcpet.application.port.PetSkinPort;
import online.yudream.base.plugin.skin.api.PluginSkinClosetItem;
import online.yudream.base.plugin.skin.api.PluginSkinProfile;
import online.yudream.base.plugin.skin.api.PluginSkinService;
import online.yudream.base.plugin.spi.core.PluginContext;

import java.util.List;
import java.util.Optional;

/**
 * yudream-skin 软依赖适配器。仅在皮肤插件 API 类可加载时实例化，
 * 否则由 {@link #create(PluginContext)} 返回空实现，避免 NoClassDefFoundError。
 * 不在跨 disable/reload 边界缓存服务实例：每次调用经 context.service 现取。
 */
public class YudreamSkinPetSkinPort implements PetSkinPort {

    private final PluginContext context;

    private YudreamSkinPetSkinPort(PluginContext context) {
        this.context = context;
    }

    public static PetSkinPort create(PluginContext context) {
        try {
            Class.forName("online.yudream.base.plugin.skin.api.PluginSkinService", false,
                    YudreamSkinPetSkinPort.class.getClassLoader());
            return new YudreamSkinPetSkinPort(context);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return PetSkinPort.unavailable();
        }
    }

    @Override
    public boolean available() {
        return service().isPresent();
    }

    @Override
    public Optional<ResolvedSkin> skinByPlayerName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }
        try {
            return service()
                    .flatMap(skin -> skin.findProfileByName(playerName.trim()))
                    .filter(profile -> profile.skin() != null && profile.skin().hash() != null)
                    .map(profile -> new ResolvedSkin(profile.skin().hash(), normalizeModel(profile.skin().model())));
        } catch (LinkageError e) {
            // 宿主运行的 yudream-skin JAR 低于编译版本时方法/类缺失，降级为空而非 500
            return Optional.empty();
        }
    }

    @Override
    public List<ClosetEntry> closetOf(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return List.of();
        }
        try {
            return service()
                    .map(skin -> skin.findClosetByOwner(ownerId.trim()).stream()
                            .map(this::toEntry)
                            .toList())
                    .orElse(List.of());
        } catch (LinkageError e) {
            // yudream-skin < 1.2.0 无 findClosetByOwner，衣柜能力降级为空列表
            return List.of();
        }
    }

    @Override
    public List<PlayerEntry> playersOf(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return List.of();
        }
        try {
            return service()
                    .map(skin -> skin.findProfilesByOwner(ownerId.trim()).stream()
                            .map(this::toEntry)
                            .toList())
                    .orElse(List.of());
        } catch (LinkageError e) {
            return List.of();
        }
    }

    @Override
    public ClosetEntry uploadSkin(String ownerId, String name, String model, String base64) {
        PluginSkinService skin = service()
                .orElseThrow(() -> new IllegalStateException("皮肤插件不可用，暂不支持上传皮肤"));
        try {
            return toEntry(skin.uploadClosetSkin(
                    ownerId.trim(), name == null ? null : name.trim(),
                    model == null ? null : model.trim(), base64));
        } catch (AbstractMethodError | NoSuchMethodError | NoClassDefFoundError e) {
            throw new IllegalStateException("皮肤插件版本过低，暂不支持上传皮肤（需要 yudream-skin ≥ 1.3.0）");
        }
    }

    private ClosetEntry toEntry(PluginSkinClosetItem item) {
        return new ClosetEntry(item.id(), item.textureHash(), item.itemName(), item.createdAt());
    }

    private PlayerEntry toEntry(PluginSkinProfile profile) {
        boolean hasSkin = profile.skin() != null && profile.skin().hash() != null;
        return new PlayerEntry(profile.name(), profile.uuid(),
                hasSkin ? profile.skin().hash() : null,
                hasSkin ? normalizeModel(profile.skin().model()) : null);
    }

    private String normalizeModel(String model) {
        return "slim".equalsIgnoreCase(model) ? "slim" : "classic";
    }

    private Optional<PluginSkinService> service() {
        try {
            return context.service("yudream-skin", PluginSkinService.class);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }
}
