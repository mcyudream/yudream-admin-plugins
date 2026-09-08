package online.yudream.base.plugin.mcpet.domain.aggregate;

import java.util.Set;

/** 用户宠物偏好；mode=default 时其余皮肤字段忽略，尺寸/停靠角/位置为对管理端默认的覆写。 */
public record PetPreference(
        String userId,
        String mode,
        String playerName,
        String closetItemId,
        Integer size,
        String corner,
        Double positionX,
        Double positionY,
        boolean hidden,
        Long updatedAt
) {
    public static final String MODE_DEFAULT = "default";
    public static final String MODE_PLAYER = "player";
    public static final String MODE_CLOSET = "closet";
    public static final Set<String> MODES = Set.of(MODE_DEFAULT, MODE_PLAYER, MODE_CLOSET);

    public PetPreference normalized() {
        String normalizedMode = mode != null && MODES.contains(mode) ? mode : MODE_DEFAULT;
        String normalizedPlayer = MODE_PLAYER.equals(normalizedMode) && playerName != null && !playerName.isBlank()
                ? playerName.trim() : null;
        String normalizedCloset = MODE_CLOSET.equals(normalizedMode) && closetItemId != null && !closetItemId.isBlank()
                ? closetItemId.trim() : null;
        Integer normalizedSize = size == null ? null : Math.max(PetDefaultsLimits.MIN, Math.min(size, PetDefaultsLimits.MAX));
        String normalizedCorner = corner != null && PetDefaultsLimits.CORNERS.contains(corner) ? corner : null;
        Double normalizedX = positionX == null ? null : Math.max(0D, Math.min(positionX, 1D));
        Double normalizedY = positionY == null ? null : Math.max(0D, Math.min(positionY, 1D));
        return new PetPreference(userId, normalizedMode, normalizedPlayer, normalizedCloset,
                normalizedSize, normalizedCorner, normalizedX, normalizedY, hidden, updatedAt);
    }

    /** 复用管理端尺寸/停靠角取值域，避免两处词表漂移。 */
    private static final class PetDefaultsLimits {
        private static final int MIN = 48;
        private static final int MAX = 320;
        private static final Set<String> CORNERS = Set.of("bottom-right", "bottom-left", "top-right", "top-left");
    }
}
