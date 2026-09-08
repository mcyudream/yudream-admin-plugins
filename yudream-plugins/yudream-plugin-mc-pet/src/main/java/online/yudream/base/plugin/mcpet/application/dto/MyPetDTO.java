package online.yudream.base.plugin.mcpet.application.dto;

/**
 * 前端挂件最终生效配置。source=builtin 时 skinUrl 为空，前端使用插件内置 Steve 皮肤。
 *
 * @param source        builtin | texture
 * @param skinUrl       皮肤 PNG 相对地址（皮肤插件匿名贴图端点）
 * @param model         classic | slim
 * @param positionX/Y   用户拖拽后的视口比例坐标（0-1），为空表示使用停靠角
 * @param clickAction   menu | greet | none
 * @param playerName    当前偏好中的角色名（mode=player 时有效），供设置页回填
 * @param closetItemId  当前偏好中的衣柜条目（mode=closet 时有效），供设置页回填
 */
public record MyPetDTO(
        String source,
        String skinUrl,
        String model,
        int size,
        String corner,
        Double positionX,
        Double positionY,
        boolean hidden,
        boolean animation,
        String clickAction,
        boolean skinAvailable,
        String preferenceMode,
        String playerName,
        String closetItemId
) {
}
