package online.yudream.base.plugin.mcpet.interfaces.request;

/** 上传皮肤请求：PNG base64（可带 data URL 前缀），model 为 classic/slim。 */
public record PetSkinUploadRequest(String name, String model, String base64) {
}
