package online.yudream.base.plugin.pony.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PonyGeneratorTest {

    @Test
    void generatedPuzzleHasUniqueDeducibleSolution() {
        for (int size = 6; size <= 9; size++) {
            PonyPuzzle puzzle = PonyGenerator.generate(size, new Random(size * 2024L));
            assertEquals(1, PonySolver.countSolutions(size, puzzle.regions(), 2), "size " + size + " 必须有唯一解");
            assertArrayEquals(puzzle.solution(), PonySolver.uniqueSolution(size, puzzle.regions()),
                    "size " + size + " 唯一解应等于生成解");
        }
    }

    @Test
    void regionsAreConnectedAndEachContainsOneSolutionCell() {
        PonyPuzzle puzzle = PonyGenerator.generate(8, new Random(42));
        int size = puzzle.size();
        for (int region = 0; region < size; region++) {
            int solutionCells = 0;
            Set<Integer> cells = new HashSet<>();
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    if (puzzle.regionAt(row, col) == region) {
                        cells.add(row * size + col);
                        if (puzzle.solutionCol(row) == col) {
                            solutionCells++;
                        }
                    }
                }
            }
            assertEquals(1, solutionCells, "色块 " + region + " 必须恰好包含一个解格");
            // 四邻连通性：从任一格子出发能覆盖整个色块
            Set<Integer> visited = new HashSet<>();
            Deque<Integer> stack = new ArrayDeque<>();
            int seed = cells.iterator().next();
            stack.push(seed);
            visited.add(seed);
            while (!stack.isEmpty()) {
                int cell = stack.pop();
                int row = cell / size;
                int col = cell % size;
                int[][] neighbors = {{row - 1, col}, {row + 1, col}, {row, col - 1}, {row, col + 1}};
                for (int[] neighbor : neighbors) {
                    if (neighbor[0] < 0 || neighbor[0] >= size || neighbor[1] < 0 || neighbor[1] >= size) {
                        continue;
                    }
                    int next = neighbor[0] * size + neighbor[1];
                    if (cells.contains(next) && visited.add(next)) {
                        stack.push(next);
                    }
                }
            }
            assertEquals(cells.size(), visited.size(), "色块 " + region + " 必须连通");
        }
    }

    @Test
    void solutionRespectsConstraints() {
        PonyPuzzle puzzle = PonyGenerator.generate(9, new Random(7));
        assertNotNull(puzzle);
        Set<Integer> cols = new HashSet<>();
        for (int row = 0; row < puzzle.size(); row++) {
            int col = puzzle.solutionCol(row);
            assertEquals(true, cols.add(col), "每列只能一匹");
            if (row > 0) {
                assertEquals(true, Math.abs(col - puzzle.solutionCol(row - 1)) > 1, "相邻行不能相邻");
            }
        }
    }
}
