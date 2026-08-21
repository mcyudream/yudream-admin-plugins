package online.yudream.base.plugin.mcguess.domain;

import java.util.List;

/**
 * 代表合成配方：3x3 网格（9 格，空位为 null）与产出数量。
 */
public record McRecipe(String result, List<String> grid, int count) {

    /** 网格中非空原料的物品 id 列表（含重复）。 */
    public List<String> ingredients() {
        return grid.stream().filter(java.util.Objects::nonNull).toList();
    }
}
