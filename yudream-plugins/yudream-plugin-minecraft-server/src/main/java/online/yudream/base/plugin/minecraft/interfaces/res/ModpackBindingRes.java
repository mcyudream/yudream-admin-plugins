package online.yudream.base.plugin.minecraft.interfaces.res;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * modpack 绑定响应 DTO（管理后台/用户前端展示）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModpackBindingRes(
        String type,
        String gameVersion,
        String loader,
        String packId,
        String versionId
) {
    public static final String TYPE_NONE = "NONE";
    public static final String TYPE_VANILLA = "VANILLA";
    public static final String TYPE_MRPACK = "MRPACK";

    public static ModpackBindingRes none() {
        return new ModpackBindingRes(TYPE_NONE, null, null, null, null);
    }
}
