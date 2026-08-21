package online.yudream.base.plugin.pony.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 谜题生成器：先随机摆出一组合法解；把其中 size-3 行的解格锁为单格色块，
 * 其余 3 个色块从解格向四邻随机扩张覆盖全盘；用求解器校验唯一解后，
 * 再在保持唯一解的前提下把小色块向邻格扩张修饰成自然形状。
 * 唯一解由求解器兜底校验，保证玩家可以仅凭推理得到最终解。
 */
public final class PonyGenerator {

    private static final int MAX_ATTEMPTS = 200;
    private static final int FREE_REGIONS = 3;

    private PonyGenerator() {
    }

    public static PonyPuzzle generate(int size) {
        return generate(size, new Random());
    }

    public static PonyPuzzle generate(int size, Random random) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int[] solution = randomSolution(size, random);
            if (solution == null) {
                continue;
            }
            int[] regions = growWithLocks(size, solution, random);
            if (regions == null || PonySolver.countSolutions(size, regions, 2) != 1) {
                continue;
            }
            decorate(size, regions, solution, random);
            if (PonySolver.countSolutions(size, regions, 2) == 1) {
                return new PonyPuzzle(size, regions, solution);
            }
        }
        throw new IllegalStateException("谜题生成失败，请重试");
    }

    /**
     * 随机合法解：逐行回溯，列不重复且与上一行不相邻（|列差| > 1）。
     */
    static int[] randomSolution(int size, Random random) {
        int[] solution = new int[size];
        boolean[] used = new boolean[size];
        return place(size, 0, used, solution, random) ? solution : null;
    }

    private static boolean place(int size, int row, boolean[] used, int[] solution, Random random) {
        if (row == size) {
            return true;
        }
        List<Integer> candidates = new ArrayList<>();
        for (int col = 0; col < size; col++) {
            if (!used[col] && (row == 0 || Math.abs(col - solution[row - 1]) > 1)) {
                candidates.add(col);
            }
        }
        while (!candidates.isEmpty()) {
            int pick = candidates.remove(random.nextInt(candidates.size()));
            solution[row] = pick;
            used[pick] = true;
            if (place(size, row + 1, used, solution, random)) {
                return true;
            }
            used[pick] = false;
        }
        return false;
    }

    /**
     * 色块铺设：随机选 3 行作为自由色块种子，其余行的解格锁为单格色块（强烈约束保证唯一解命中率），
     * 自由色块随机向未分配的四邻格生长直至覆盖全盘。出现被锁格围死、无法覆盖的死角时返回 null 重试。
     */
    static int[] growWithLocks(int size, int[] solution, Random random) {
        int[] regions = new int[size * size];
        Arrays.fill(regions, -1);
        List<Integer> rows = new ArrayList<>();
        for (int row = 0; row < size; row++) {
            rows.add(row);
        }
        Collections.shuffle(rows, random);
        boolean[] freeRegion = new boolean[size];
        for (int i = 0; i < Math.min(FREE_REGIONS, size); i++) {
            freeRegion[rows.get(i)] = true;
        }
        for (int row = 0; row < size; row++) {
            regions[row * size + solution[row]] = row;
        }
        int assigned = size;
        while (assigned < size * size) {
            List<int[]> frontier = new ArrayList<>();
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    int region = regions[row * size + col];
                    if (region < 0 || !freeRegion[region]) {
                        continue;
                    }
                    if (row > 0 && regions[(row - 1) * size + col] < 0) {
                        frontier.add(new int[]{region, row - 1, col});
                    }
                    if (row < size - 1 && regions[(row + 1) * size + col] < 0) {
                        frontier.add(new int[]{region, row + 1, col});
                    }
                    if (col > 0 && regions[row * size + col - 1] < 0) {
                        frontier.add(new int[]{region, row, col - 1});
                    }
                    if (col < size - 1 && regions[row * size + col + 1] < 0) {
                        frontier.add(new int[]{region, row, col + 1});
                    }
                }
            }
            if (frontier.isEmpty()) {
                return null;
            }
            int[] step = frontier.get(random.nextInt(frontier.size()));
            regions[step[1] * size + step[2]] = step[0];
            assigned++;
        }
        return regions;
    }

    /**
     * 形状修饰：把过小的色块向相邻格扩张。每次移动都保持分区完整、各色块连通且唯一解不变，
     * 不满足就撤销，因此修饰不会破坏「可以推理出最终解」的保证；实在扩不动的色块保持原样。
     */
    static void decorate(int size, int[] regions, int[] solution, Random random) {
        for (int target = 2; target <= 3; target++) {
            for (int region = 0; region < size; region++) {
                growRegionTo(size, regions, solution, region, target, random);
            }
        }
    }

    private static void growRegionTo(int size, int[] regions, int[] solution, int region, int target, Random random) {
        int guard = 0;
        while (cellsOf(size, regions, region).size() < target && guard++ < 40) {
            List<Integer> cells = cellsOf(size, regions, region);
            int cell = cells.get(random.nextInt(cells.size()));
            int row = cell / size;
            int col = cell % size;
            List<int[]> neighbors = new ArrayList<>();
            if (row > 0) {
                neighbors.add(new int[]{row - 1, col});
            }
            if (row < size - 1) {
                neighbors.add(new int[]{row + 1, col});
            }
            if (col > 0) {
                neighbors.add(new int[]{row, col - 1});
            }
            if (col < size - 1) {
                neighbors.add(new int[]{row, col + 1});
            }
            Collections.shuffle(neighbors, random);
            for (int[] neighbor : neighbors) {
                int index = neighbor[0] * size + neighbor[1];
                int host = regions[index];
                if (host == region || index == host * size + solution[host]) {
                    continue;
                }
                regions[index] = region;
                if (connected(size, regions, host) && PonySolver.countSolutions(size, regions, 2) == 1) {
                    break;
                }
                regions[index] = host;
            }
        }
    }

    private static List<Integer> cellsOf(int size, int[] regions, int region) {
        List<Integer> cells = new ArrayList<>();
        for (int index = 0; index < size * size; index++) {
            if (regions[index] == region) {
                cells.add(index);
            }
        }
        return cells;
    }

    private static boolean connected(int size, int[] regions, int region) {
        List<Integer> cells = cellsOf(size, regions, region);
        if (cells.size() <= 1) {
            return true;
        }
        boolean[] seen = new boolean[size * size];
        List<Integer> queue = new ArrayList<>();
        queue.add(cells.get(0));
        seen[cells.get(0)] = true;
        int reached = 0;
        while (!queue.isEmpty()) {
            int cell = queue.remove(queue.size() - 1);
            reached++;
            int row = cell / size;
            int col = cell % size;
            int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int[] dir : dirs) {
                int r = row + dir[0];
                int c = col + dir[1];
                if (r < 0 || r >= size || c < 0 || c >= size) {
                    continue;
                }
                int next = r * size + c;
                if (!seen[next] && regions[next] == region) {
                    seen[next] = true;
                    queue.add(next);
                }
            }
        }
        return reached == cells.size();
    }
}
