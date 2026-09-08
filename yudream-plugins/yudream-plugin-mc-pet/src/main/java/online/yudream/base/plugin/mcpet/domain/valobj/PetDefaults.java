package online.yudream.base.plugin.mcpet.domain.valobj;

import java.util.Set;

/** 管理端全局默认配置：皮肤来源、交互风格、尺寸与默认停靠角。 */
public record PetDefaults(
        String mode,
        String playerName,
        String textureHash,
        String model,
        boolean animation,
        String clickAction,
        int size,
        String corner
) {
    public static final String MODE_BUILTIN = "builtin";
    public static final String MODE_PLAYER = "player";
    public static final String MODE_TEXTURE = "texture";

    public static final Set<String> MODELS = Set.of("classic", "slim");
    public static final Set<String> CLICK_ACTIONS = Set.of("menu", "greet", "none");
    public static final Set<String> CORNERS = Set.of("bottom-right", "bottom-left", "top-right", "top-left");
    public static final int MIN_SIZE = 48;
    public static final int MAX_SIZE = 320;

    public static PetDefaults builtin() {
        return new PetDefaults(MODE_BUILTIN, null, null, "classic", true, "menu", 120, "bottom-right");
    }

    public PetDefaults normalized() {
        String normalizedMode = MODE_PLAYER.equals(mode) || MODE_TEXTURE.equals(mode) ? mode : MODE_BUILTIN;
        String normalizedPlayer = MODE_PLAYER.equals(normalizedMode) && playerName != null && !playerName.isBlank()
                ? playerName.trim() : null;
        String normalizedHash = MODE_TEXTURE.equals(normalizedMode) && textureHash != null && !textureHash.isBlank()
                ? textureHash.trim() : null;
        String normalizedModel = model != null && MODELS.contains(model) ? model : "classic";
        String normalizedAction = clickAction != null && CLICK_ACTIONS.contains(clickAction) ? clickAction : "menu";
        int normalizedSize = size < MIN_SIZE ? MIN_SIZE : Math.min(size, MAX_SIZE);
        String normalizedCorner = corner != null && CORNERS.contains(corner) ? corner : "bottom-right";
        return new PetDefaults(normalizedMode, normalizedPlayer, normalizedHash, normalizedModel,
                animation, normalizedAction, normalizedSize, normalizedCorner);
    }
}
