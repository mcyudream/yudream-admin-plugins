package online.yudream.base.plugin.mcguess.domain;

import online.yudream.base.plugin.mcguess.infrastructure.McDataLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 棋盘生成器：生成的 3x3 条件棋盘必须每格有候选、整盘存在不重复填法。
 */
class MobBoardGeneratorTest {

    private static McMobCatalog catalog;

    @BeforeAll
    static void load() {
        catalog = McDataLoader.loadMobs(MobBoardGeneratorTest.class.getClassLoader());
    }

    @Test
    void generatedBoardsAreAlwaysSolvable() {
        MobBoardGenerator generator = new MobBoardGenerator(catalog);
        Random random = new Random(20260821L);
        for (int i = 0; i < 100; i++) {
            MobBoardGenerator.MobBoard board = generator.generate(random);
            assertEquals(3, board.rows().size());
            assertEquals(3, board.cols().size());
            assertEquals(9, board.solution().size());
            Set<String> used = new HashSet<>();
            for (int cell = 0; cell < 9; cell++) {
                String row = board.rows().get(cell / 3);
                String col = board.cols().get(cell % 3);
                String mobId = board.solution().get(cell);
                assertNotNull(mobId, "答案格不能为空");
                McMobCatalog.McMob mob = catalog.byId(mobId).orElseThrow();
                assertTrue(mob.cond().contains(row) && mob.cond().contains(col),
                        mobId + " 必须同时满足行条件 " + row + " 与列条件 " + col);
                assertTrue(used.add(mobId), "同盘答案不能重复：" + mobId);
            }
        }
    }

    @Test
    void perfectMatchingFindsDistinctAssignment() {
        List<List<String>> cells = List.of(
                List.of("a", "b"), List.of("a", "b"), List.of("b", "c"));
        List<String> solution = MobBoardGenerator.perfectMatching(cells);
        assertNotNull(solution);
        assertEquals(3, new HashSet<>(solution).size(), "填法必须互不重复");
        for (int i = 0; i < cells.size(); i++) {
            assertTrue(cells.get(i).contains(solution.get(i)));
        }
    }

    @Test
    void perfectMatchingReturnsNullWhenUnsolvable() {
        // 三格只能填同一个生物，必然无解
        assertNull(MobBoardGenerator.perfectMatching(List.of(
                List.of("a"), List.of("a"), List.of("a", "b"), List.of("b"))));
    }
}
