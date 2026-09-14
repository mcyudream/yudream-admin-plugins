package online.yudream.base.plugin.minecraft.interfaces.request;

/**
 * 周目绑定 YMCL 整合包或原版版本。
 */
public record MinecraftSeasonBindingRequest(
        String type,
        String gameVersion,
        String loader,
        String packId,
        String versionId
) {
}
