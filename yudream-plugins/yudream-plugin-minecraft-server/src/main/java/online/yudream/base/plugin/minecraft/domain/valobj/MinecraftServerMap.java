package online.yudream.base.plugin.minecraft.domain.valobj;

/** A single ZIP attachment owned by a Minecraft server. */
public record MinecraftServerMap(String fileId, String objectKey, String originalName, boolean publicAccess) {
    public MinecraftServerMap {
        if (fileId == null || fileId.isBlank()) throw new IllegalArgumentException("地图文件不能为空");
        objectKey = objectKey == null ? "" : objectKey;
        originalName = originalName == null || originalName.isBlank() ? fileId + ".zip" : originalName.trim();
    }

    public MinecraftServerMap withPublicAccess(boolean value) {
        return new MinecraftServerMap(fileId, objectKey, originalName, value);
    }
}
