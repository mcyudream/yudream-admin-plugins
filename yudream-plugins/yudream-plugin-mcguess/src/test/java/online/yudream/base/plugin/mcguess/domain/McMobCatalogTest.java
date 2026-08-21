package online.yudream.base.plugin.mcguess.domain;

import online.yudream.base.plugin.mcguess.infrastructure.McDataLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于真实数据集（mcguess/mcmobs.json）验证生物目录加载、中文名匹配与条件索引。
 */
class McMobCatalogTest {

    private static McMobCatalog catalog;

    @BeforeAll
    static void load() {
        catalog = McDataLoader.loadMobs(McMobCatalogTest.class.getClassLoader());
    }

    @Test
    void datasetCoversCommonMobs() {
        assertTrue(catalog.mobs().size() >= 60, "生物数量不能太少");
        assertTrue(catalog.conditions().size() >= 15, "条件数量不能太少");
    }

    @Test
    void everyMobConditionExists() {
        for (McMobCatalog.McMob mob : catalog.mobs()) {
            assertFalse(mob.cond().isEmpty(), mob.id() + " 至少应满足一个条件");
            for (String code : mob.cond()) {
                assertTrue(catalog.condition(code).isPresent(), mob.id() + " 引用了不存在的条件 " + code);
            }
        }
    }

    @Test
    void everyConditionHasEnoughCandidates() {
        // 条件候选太少会导致棋盘生成频繁重抽
        for (McMobCatalog.McCondition condition : catalog.conditions()) {
            assertTrue(catalog.candidates(condition.code()).size() >= 3,
                    condition.code() + " 候选生物过少");
        }
    }

    @Test
    void matchByExactZhName() {
        Optional<McMobCatalog.McMob> zombie = catalog.match("僵尸");
        assertTrue(zombie.isPresent());
        assertEquals("zombie", zombie.get().id());
        // 空白与间隔符会被忽略
        assertEquals("zombie", catalog.match(" 僵 尸 ").orElseThrow().id());
        assertTrue(catalog.match("").isEmpty());
        assertTrue(catalog.match(null).isEmpty());
        assertTrue(catalog.match("不存在的生物").isEmpty());
    }

    @Test
    void intersectionSatisfiesBothConditions() {
        List<McMobCatalog.McMob> undeadHostile = catalog.intersection("undead", "hostile");
        assertTrue(undeadHostile.stream().anyMatch(mob -> mob.id().equals("zombie")));
        for (McMobCatalog.McMob mob : undeadHostile) {
            assertTrue(mob.cond().contains("undead") && mob.cond().contains("hostile"));
        }
        // 末影龙不是亡灵
        assertTrue(catalog.intersection("undead", "end").stream().noneMatch(mob -> mob.id().equals("ender_dragon")));
    }

    @Test
    void eggIconIdFollowsIconNaming() {
        assertEquals("zombie_spawn_egg", catalog.eggIconId("zombie"));
    }
}
