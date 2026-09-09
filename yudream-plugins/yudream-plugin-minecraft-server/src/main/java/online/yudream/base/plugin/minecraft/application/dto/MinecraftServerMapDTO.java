package online.yudream.base.plugin.minecraft.application.dto;

public record MinecraftServerMapDTO(String fileId, String originalName, boolean publicAccess, String externalUrl) {
    public MinecraftServerMapDTO withoutExternalUrl() {
        return new MinecraftServerMapDTO(fileId, originalName, publicAccess, null);
    }
}
