package online.yudream.plugin.worldmap.infrastructure.world;

import java.util.Map;

/**
 * 方块状态：名称 + 属性表。
 *
 * @param name       形如 "minecraft:oak_log" 的命名空间 ID
 * @param properties 方块属性（如 axis=y），不可为空
 */
public record BlockState(String name, Map<String, String> properties) {

    /** 空气方块（minecraft:air，无属性）。 */
    public static final BlockState AIR = new BlockState("minecraft:air", Map.of());

    public BlockState {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    /** 是否为任意一种空气（air / cave_air / void_air）。 */
    public boolean isAir() {
        return switch (name) {
            case "minecraft:air", "minecraft:cave_air", "minecraft:void_air" -> true;
            default -> false;
        };
    }
}
