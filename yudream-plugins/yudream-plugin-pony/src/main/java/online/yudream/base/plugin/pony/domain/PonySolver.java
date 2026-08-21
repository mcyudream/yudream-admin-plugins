package online.yudream.base.plugin.pony.domain;

/**
 * 谜题求解器：回溯枚举「每行 1 匹、每列 1 匹、每色 1 匹、互不相邻」的摆法。
 * countSolutions 带计数上限，用于校验生成谜题的唯一解（从而保证可推导出最终解）。
 */
public final class PonySolver {

    private PonySolver() {
    }

    public static int countSolutions(int size, int[] regions, int cap) {
        return count(size, regions, cap, 0, new boolean[size], new boolean[size], -1, new int[size]);
    }

    /**
     * 求出唯一解（每行所在列）；无解或解不唯一时返回 null。
     */
    public static int[] uniqueSolution(int size, int[] regions) {
        int[] found = new int[size];
        int[] current = new int[size];
        int[] counter = {0};
        solve(size, regions, 0, new boolean[size], new boolean[size], -1, current, found, counter);
        return counter[0] == 1 ? found : null;
    }

    private static int count(int size, int[] regions, int cap, int row,
                             boolean[] usedCols, boolean[] usedRegions, int prevCol, int[] current) {
        if (row == size) {
            return 1;
        }
        int total = 0;
        for (int col = 0; col < size; col++) {
            int region = regions[row * size + col];
            if (usedCols[col] || usedRegions[region] || (prevCol >= 0 && Math.abs(col - prevCol) <= 1)) {
                continue;
            }
            usedCols[col] = true;
            usedRegions[region] = true;
            total += count(size, regions, cap, row + 1, usedCols, usedRegions, col, current);
            usedCols[col] = false;
            usedRegions[region] = false;
            if (total >= cap) {
                return total;
            }
        }
        return total;
    }

    private static void solve(int size, int[] regions, int row,
                              boolean[] usedCols, boolean[] usedRegions, int prevCol,
                              int[] current, int[] found, int[] counter) {
        if (counter[0] > 1) {
            return;
        }
        if (row == size) {
            counter[0]++;
            if (counter[0] == 1) {
                System.arraycopy(current, 0, found, 0, size);
            }
            return;
        }
        for (int col = 0; col < size; col++) {
            int region = regions[row * size + col];
            if (usedCols[col] || usedRegions[region] || (prevCol >= 0 && Math.abs(col - prevCol) <= 1)) {
                continue;
            }
            usedCols[col] = true;
            usedRegions[region] = true;
            current[row] = col;
            solve(size, regions, row + 1, usedCols, usedRegions, col, current, found, counter);
            usedCols[col] = false;
            usedRegions[region] = false;
        }
    }
}
