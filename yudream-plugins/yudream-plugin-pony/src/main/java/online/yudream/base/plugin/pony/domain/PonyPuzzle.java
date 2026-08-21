package online.yudream.base.plugin.pony.domain;

/**
 * 一局小马归位谜题：size×size 棋盘被划分为 size 个连通色块，
 * 每行、每列、每种颜色恰好 1 匹小马，且任意两匹小马（含斜角）不相邻。
 * regions 按行优先存储（第 0 行 = 棋盘底部第 1 行），solution 为每行小马所在列（0 起）。
 */
public record PonyPuzzle(int size, int[] regions, int[] solution) {

    public int regionAt(int row, int col) {
        return regions[row * size + col];
    }

    public int solutionCol(int row) {
        return solution[row];
    }
}
