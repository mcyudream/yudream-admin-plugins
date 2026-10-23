package online.yudream.base.plugin.skin.domain.aggregate;

import java.util.Locale;

public record SkinPlayer(
        String uuid,
        String ownerId,
        String name,
        String nameLower,
        String skinHash,
        String capeHash,
        Long migratedPid,
        Long lastModified,
        /** offline（默认，兼容旧文档缺省）| official（正版绑定角色，UUID = 官方 UUID）。 */
        String profileKind,
        /**
         * 新离线角色的不可变联合角色 ID（随机 128 位 hex）：参与 UUIDv5 派生，
         * 不随改名/删除重建复用。旧角色缺省为空，保持其历史 UUID 不迁移。
         */
        String offlineIdentityId
) {
    /** 兼容旧调用（未指定 kind 的记录按 offline 处理）。 */
    public SkinPlayer {
        if (profileKind == null || profileKind.isBlank()) {
            profileKind = "offline";
        }
    }

    public static final String KIND_OFFLINE = "offline";
    public static final String KIND_OFFICIAL = "official";

    public SkinPlayer withTextures(String newSkinHash, String newCapeHash) {
        return new SkinPlayer(uuid, ownerId, name, nameLower, newSkinHash, newCapeHash, migratedPid,
                System.currentTimeMillis(), profileKind, offlineIdentityId);
    }

    public SkinPlayer withName(String newName) {
        String normalized = newName == null ? null : newName.trim();
        return new SkinPlayer(
                uuid,
                ownerId,
                normalized,
                normalized == null ? null : normalized.toLowerCase(Locale.ROOT),
                skinHash,
                capeHash,
                migratedPid,
                System.currentTimeMillis(),
                profileKind,
                offlineIdentityId
        );
    }
}
