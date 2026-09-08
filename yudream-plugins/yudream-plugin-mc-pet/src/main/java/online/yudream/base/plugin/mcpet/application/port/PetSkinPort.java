package online.yudream.base.plugin.mcpet.application.port;

import java.util.List;
import java.util.Optional;

/**
 * 皮肤数据源端口（yudream-skin 软依赖）。实现必须隔离可选类引用，
 * 皮肤插件缺失时返回 {@link #unavailable()} 空实现，插件主体仍可运行。
 */
public interface PetSkinPort {

    boolean available();

    /** 按角色名解析皮肤；角色不存在或无皮肤时为空。 */
    Optional<ResolvedSkin> skinByPlayerName(String playerName);

    /** 指定用户的全部衣柜条目（只读）。 */
    List<ClosetEntry> closetOf(String ownerId);

    /** 指定用户的全部角色（只读）。 */
    List<PlayerEntry> playersOf(String ownerId);

    /**
     * 上传皮肤 PNG（base64）到指定用户的皮肤库并加入衣柜，返回新衣柜条目。
     * 皮肤插件缺失或版本过低不支持上传时抛出 {@link IllegalStateException}；
     * 皮肤插件的校验失败（非 PNG 等）以 {@link IllegalArgumentException} 透出。
     */
    ClosetEntry uploadSkin(String ownerId, String name, String model, String base64);

    record ResolvedSkin(String textureHash, String model) {
    }

    record ClosetEntry(String id, String textureHash, String itemName, Long createdAt) {
    }

    record PlayerEntry(String name, String uuid, String textureHash, String model) {
    }

    static PetSkinPort unavailable() {
        return new PetSkinPort() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Optional<ResolvedSkin> skinByPlayerName(String playerName) {
                return Optional.empty();
            }

            @Override
            public List<ClosetEntry> closetOf(String ownerId) {
                return List.of();
            }

            @Override
            public List<PlayerEntry> playersOf(String ownerId) {
                return List.of();
            }

            @Override
            public ClosetEntry uploadSkin(String ownerId, String name, String model, String base64) {
                throw new IllegalStateException("皮肤插件不可用，暂不支持上传皮肤");
            }
        };
    }
}
