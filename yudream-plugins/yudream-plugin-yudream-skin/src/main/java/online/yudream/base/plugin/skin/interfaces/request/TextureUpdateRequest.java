package online.yudream.base.plugin.skin.interfaces.request;

public record TextureUpdateRequest(
        String name,
        Boolean publicAccess
) {
}
