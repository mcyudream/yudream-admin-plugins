package online.yudream.base.plugin.material.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.material.application.dto.ShareView;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.domain.MaterialShare;
import online.yudream.base.plugin.material.infrastructure.ShareRepository;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;

/** 分享外链用例：创建/列表/撤销走归属校验；公开端解析仅校验 token 存在、未过期、物料未删除。 */
public final class ShareService {
    private static final int MAX_HOURS = 8760;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShareRepository shares;
    private final MaterialService materialService;
    private final FrameworkServices framework;

    public ShareService(ShareRepository shares, MaterialService materialService, FrameworkServices framework) {
        this.shares = shares;
        this.materialService = materialService;
        this.framework = framework;
    }

    /** expiresInHours 为 null 表示永久有效；否则 1..8760 小时。 */
    public ShareView create(String ownerId, String materialId, Integer expiresInHours, String note) {
        Material material = materialService.requireOwn(ownerId, materialId);
        return doCreate(ownerId, material, expiresInHours, note);
    }

    /** 管理端代创建：不校验归属，创建人记操作者。 */
    public ShareView createAs(String operatorId, String materialId, Integer expiresInHours, String note) {
        Material material = materialService.requireAny(materialId);
        return doCreate(operatorId, material, expiresInHours, note);
    }

    private ShareView doCreate(String actorId, Material material, Integer expiresInHours, String note) {
        long now = System.currentTimeMillis();
        long expiresAt = 0;
        if (expiresInHours != null) {
            if (expiresInHours < 1 || expiresInHours > MAX_HOURS) {
                throw new IllegalArgumentException("有效期需在 1 小时到 1 年之间");
            }
            expiresAt = now + expiresInHours * 3600_000L;
        }
        String normalizedNote = note == null || note.isBlank() ? null : note.trim();
        if (normalizedNote != null && normalizedNote.length() > 100) {
            throw new IllegalArgumentException("备注不能超过 100 字");
        }
        MaterialShare share = new MaterialShare(newToken(), material.id(), actorId, resolveUserName(actorId),
                normalizedNote, expiresAt, now);
        shares.save(share);
        return ShareView.from(share, now);
    }

    public List<ShareView> list(String ownerId, String materialId) {
        Material material = materialService.requireOwn(ownerId, materialId);
        return listOf(material.id());
    }

    /** 管理端列出任意物料的分享。 */
    public List<ShareView> listOf(String materialId) {
        Material material = materialService.requireAny(materialId);
        long now = System.currentTimeMillis();
        return shares.listByMaterial(material.id()).stream()
                .sorted(Comparator.comparingLong(MaterialShare::createdAt).reversed())
                .map(share -> ShareView.from(share, now))
                .toList();
    }

    public void revoke(String ownerId, String materialId, String shareId) {
        materialService.requireOwn(ownerId, materialId);
        revokeChecked(materialId, shareId);
    }

    /** 管理端撤销：不校验归属，仍要求分享确实属于该物料。 */
    public void revokeAny(String materialId, String shareId) {
        materialService.requireAny(materialId);
        revokeChecked(materialId, shareId);
    }

    private void revokeChecked(String materialId, String shareId) {
        MaterialShare share = shares.findByToken(shareId)
                .orElseThrow(() -> new NotFoundException("分享不存在"));
        if (!materialId.equals(share.materialId())) {
            throw new NotFoundException("分享不存在");
        }
        shares.delete(share.token());
    }

    /** 公开端解析：token 存在且未过期、物料未删除。归档不影响已发链接（软隐藏非安全边界，与签名 token 一致）。 */
    public Material resolveValid(String token) {
        MaterialShare share = shares.findByToken(token == null ? "" : token)
                .orElseThrow(() -> new NotFoundException("分享链接无效或已过期"));
        if (share.expired(System.currentTimeMillis())) {
            throw new NotFoundException("分享链接无效或已过期");
        }
        return materialService.requireAny(share.materialId());
    }

    private static String newToken() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String resolveUserName(String ownerId) {
        try {
            Optional<PluginUserProfile> profile = framework.users().findById(Long.parseLong(ownerId));
            return profile.map(user -> user.nickname() != null && !user.nickname().isBlank()
                    ? user.nickname() : user.username()).orElse(null);
        }
        catch (Exception e) {
            return null;
        }
    }
}
