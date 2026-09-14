package online.yudream.base.plugin.minecraft.domain.valobj;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 启动器侧用于按需下载的 modpack 绑定抽象（周目级）。
 * <p>
 * 三种形态，保持类名稳定且对 mc-server 业务透明 -- 版本与格式字符串是契约，
 * 适配器/启动器负责将字符串解释为可下载的资源描述。
 *
 * @param type          绑定类型：NONE（无绑定）/ VANILLA（原版/小游戏）/ MRPACK（mrpack 协议）
 * @param gameVersion   仅 VANILLA 时生效：游戏版本号（如 "1.21.4"）
 * @param loader        仅 VANILLA 时生效：模组加载器（如 "fabric"/"forge"/"vanilla"），用于启动器侧匹配本地实例
 * @param packId        仅 MRPACK 时生效：launcher-adapter 的 pack id
 * @param versionId     仅 MRPACK 时生效：版本号（SemVer），缺省取适配器的 recommendedVersion
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModpackBinding(
        Type type,
        String gameVersion,
        String loader,
        String packId,
        String versionId
) {

    public enum Type {
        NONE,
        VANILLA,
        MRPACK
    }

    public ModpackBinding {
        if (type == null) {
            type = Type.NONE;
        }
        switch (type) {
            case NONE -> {
                if (nonEmpty(gameVersion) || nonEmpty(loader) || nonEmpty(packId) || nonEmpty(versionId)) {
                    throw new IllegalArgumentException("NONE 类型的绑定不应携带其它字段");
                }
            }
            case VANILLA -> {
                requireText(gameVersion, "VANILLA 绑定需要 gameVersion");
                requireText(loader, "VANILLA 绑定需要 loader");
                if (nonEmpty(packId) || nonEmpty(versionId)) {
                    throw new IllegalArgumentException("VANILLA 绑定不应携带 packId/versionId");
                }
            }
            case MRPACK -> {
                requireText(packId, "MRPACK 绑定需要 packId");
                if (nonEmpty(gameVersion) || nonEmpty(loader)) {
                    throw new IllegalArgumentException("MRPACK 绑定不应携带 gameVersion/loader");
                }
            }
        }
    }

    /** NONE 绑定默认值，等价于不绑定，启动器回退到本地实例匹配。 */
    public static ModpackBinding none() {
        return new ModpackBinding(Type.NONE, null, null, null, null);
    }

    /** 原版/小游戏服绑定；loader 限定为 vanilla/fabric/forge/neoforge/paper 之类，启动器侧用其匹配本地实例。 */
    public static ModpackBinding vanilla(String gameVersion, String loader) {
        return new ModpackBinding(Type.VANILLA, gameVersion, loader, null, null);
    }

    /** mrpack 绑定；versionId 可空，缺省取适配器 recommendedVersion。 */
    public static ModpackBinding mrpack(String packId, String versionId) {
        return new ModpackBinding(Type.MRPACK, null, null, packId, versionId);
    }

    @JsonIgnore
    public boolean isBound() {
        return type != Type.NONE;
    }

    @JsonIgnore
    public boolean isVanilla() {
        return type == Type.VANILLA;
    }

    @JsonIgnore
    public boolean isMrpack() {
        return type == Type.MRPACK;
    }

    private static boolean nonEmpty(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
