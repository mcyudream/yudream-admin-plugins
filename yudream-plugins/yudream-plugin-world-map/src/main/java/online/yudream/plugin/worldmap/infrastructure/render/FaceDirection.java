package online.yudream.plugin.worldmap.infrastructure.render;

import java.util.HashMap;
import java.util.Map;

/**
 * 立方体六向：方向向量、所属轴向、遮挡位掩码与相反面。
 * 枚举按相反面成对排列（ordinal ^ 1 即相反面）。
 */
enum FaceDirection {
    DOWN("down", 0, -1, 0, 1, 1, false),
    UP("up", 0, 1, 0, 2, 1, true),
    NORTH("north", 0, 0, -1, 4, 2, false),
    SOUTH("south", 0, 0, 1, 8, 2, true),
    EAST("east", 1, 0, 0, 16, 0, true),
    WEST("west", -1, 0, 0, 32, 0, false);

    /** cullface 名称。 */
    final String cullfaceName;
    final int dx;
    final int dy;
    final int dz;
    /** 六向遮挡掩码位。 */
    final int bit;
    /** 法线所属轴：0=x 1=y 2=z。 */
    final int axis;
    /** 是否朝轴正方向（决定面片平面在局部 16 还是 0）。 */
    final boolean positive;

    FaceDirection(String cullfaceName, int dx, int dy, int dz, int bit, int axis, boolean positive) {
        this.cullfaceName = cullfaceName;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.bit = bit;
        this.axis = axis;
        this.positive = positive;
    }

    FaceDirection opposite() {
        return values()[ordinal() ^ 1];
    }

    private static final Map<String, FaceDirection> BY_NAME = new HashMap<>();

    static {
        for (FaceDirection d : values()) {
            BY_NAME.put(d.cullfaceName, d);
        }
    }

    /** 按 cullface 名称查找；null 或未知名称返回 null。 */
    static FaceDirection byName(String cullface) {
        return cullface == null ? null : BY_NAME.get(cullface);
    }
}
