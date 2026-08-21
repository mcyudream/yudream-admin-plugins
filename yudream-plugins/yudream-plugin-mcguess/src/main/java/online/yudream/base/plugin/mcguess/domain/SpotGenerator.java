package online.yudream.base.plugin.mcguess.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 找茬出题器：取一个真实 3x3 配方，把某一非空格替换为违和物品——
 * 优先同族变体（如红色羊毛换成蓝色羊毛，考验观察力），无同族时换成配方外的其他带图标物品。
 */
public class SpotGenerator {

    /** 出题重试上限（配方原料不足或无可用替换物时换目标）。 */
    private static final int MAX_ATTEMPTS = 400;

    private final McCatalog catalog;

    public SpotGenerator(McCatalog catalog) {
        this.catalog = catalog;
    }

    public SpotPuzzle generate(Random random) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            McItem target = catalog.randomTarget(random);
            McRecipe recipe = catalog.recipeOf(target.id()).orElse(null);
            if (recipe == null) {
                continue;
            }
            List<Integer> nonEmpty = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                if (recipe.grid().get(i) != null) {
                    nonEmpty.add(i);
                }
            }
            if (nonEmpty.size() < 2) {
                continue;
            }
            int wrongIndex = nonEmpty.get(random.nextInt(nonEmpty.size()));
            String correctId = recipe.grid().get(wrongIndex);
            McItem replacement = pickReplacement(correctId, recipe.grid(), random);
            if (replacement == null) {
                continue;
            }
            List<String> displayed = new ArrayList<>(recipe.grid());
            displayed.set(wrongIndex, replacement.id());
            return new SpotPuzzle(target.id(), displayed, wrongIndex + 1, correctId);
        }
        throw new IllegalStateException("找茬出题失败：没有可用的配方");
    }

    /** 替换物：优先同族带图标变体；否则取配方之外的随机带图标物品。 */
    private McItem pickReplacement(String correctId, List<String> grid, Random random) {
        List<McItem> family = catalog.familyOf(correctId).stream()
                .filter(McItem::icon)
                .filter(item -> !grid.contains(item.id()))
                .toList();
        if (!family.isEmpty()) {
            return family.get(random.nextInt(family.size()));
        }
        List<McItem> outsiders = catalog.iconItems().stream()
                .filter(item -> !item.id().equals(correctId))
                .filter(item -> !grid.contains(item.id()))
                .toList();
        if (outsiders.isEmpty()) {
            return null;
        }
        return outsiders.get(random.nextInt(outsiders.size()));
    }

    /**
     * 找茬题目。
     *
     * @param targetId  配方产物物品 id
     * @param grid      展示用 3x3 网格（9 格，空位为 null；错误格已是替换后的物品）
     * @param wrongCell 错误格序号（1-9）
     * @param correctId 错误格原本应该是的物品 id
     */
    public record SpotPuzzle(String targetId, List<String> grid, int wrongCell, String correctId) {
    }
}
