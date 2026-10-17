package online.yudream.plugin.voxelithmap.domain.value;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 维度 ↔ 存档目录的对应关系。
 *
 * <p>实例导入按这份对应把「实例里的目录」变成「一张地图 + 一个维度 + 一个存档根提示」；
 * 映射错了就是渲染错维度（白烘一张图），所以逐个钉住。</p>
 */
class InstanceDimensionDirTest {

    @Test
    void mapsDimensionsToVanillaDirectories() {
        assertEquals("world", InstanceDimensionDir.dirFor("minecraft:overworld"));
        assertEquals("world_nether", InstanceDimensionDir.dirFor("minecraft:nether"));
        assertEquals("world_the_end", InstanceDimensionDir.dirFor("minecraft:the_end"));
        // 空/未知维度按主世界处理，不抛错（新建弹窗维度未定时也不能炸）
        assertEquals("world", InstanceDimensionDir.dirFor(null));
        assertEquals("world", InstanceDimensionDir.dirFor("minecraft:custom"));
    }

    @Test
    void recognisesDirectoriesIncludingLegacyNames() {
        assertEquals("minecraft:overworld", InstanceDimensionDir.dimensionOf("world").orElseThrow());
        assertEquals("minecraft:nether", InstanceDimensionDir.dimensionOf("world_nether").orElseThrow());
        assertEquals("minecraft:the_end", InstanceDimensionDir.dimensionOf("world_the_end").orElseThrow());
        assertEquals("minecraft:nether", InstanceDimensionDir.dimensionOf("DIM-1").orElseThrow());
        assertEquals("minecraft:the_end", InstanceDimensionDir.dimensionOf("DIM1").orElseThrow());
        assertTrue(InstanceDimensionDir.dimensionOf("plugins").isEmpty());
        assertTrue(InstanceDimensionDir.dimensionOf("world.zip").isEmpty());
        assertTrue(InstanceDimensionDir.dimensionOf(null).isEmpty());
    }

    @Test
    void picksPresentDirectoriesInAFixedOrder() {
        List<InstanceDimensionDir> picked = InstanceDimensionDir.pick(List.of(
                "logs", "world_the_end", "mods", "world_nether", "world"));

        assertEquals(List.of("world", "world_nether", "world_the_end"),
                picked.stream().map(InstanceDimensionDir::dirName).toList());
        assertEquals(List.of("minecraft:overworld", "minecraft:nether", "minecraft:the_end"),
                picked.stream().map(InstanceDimensionDir::dimension).toList());
        assertTrue(InstanceDimensionDir.pick(List.of("mods", "logs")).isEmpty());
        assertTrue(InstanceDimensionDir.pick(List.of()).isEmpty());
    }

    /**
     * 旧版服务端的 {@code DIM-1}/{@code DIM1} 也要认，且 {@code dirName} 保留真实目录名。
     *
     * <p>打包要按真实路径走、地图的 {@code worldName} 得与包内目录名一致，渲染时才能定位到
     * 对应维度的存档根；若在这里归一化成 world_nether，包里的 DIM-1 目录就再也找不到了。</p>
     */
    @Test
    void picksLegacyDimensionDirectoriesKeepingTheirRealNames() {
        List<InstanceDimensionDir> picked = InstanceDimensionDir.pick(List.of("DIM-1", "DIM1", "world"));

        assertEquals(List.of("world", "DIM-1", "DIM1"),
                picked.stream().map(InstanceDimensionDir::dirName).toList());
        assertEquals(List.of("minecraft:overworld", "minecraft:nether", "minecraft:the_end"),
                picked.stream().map(InstanceDimensionDir::dimension).toList());
    }

    @Test
    void labelsAreChinese() {
        assertEquals("主世界", InstanceDimensionDir.label("minecraft:overworld"));
        assertEquals("下界", InstanceDimensionDir.label("minecraft:nether"));
        assertEquals("末地", InstanceDimensionDir.label("minecraft:the_end"));
        assertEquals("主世界", InstanceDimensionDir.label(null));
    }

    /**
     * 老版布局（{@code world/DIM-1}、{@code world/DIM1}）：目录名取新版规范名，打包路径指向父目录。
     *
     * <p>打包路径必须带父目录：DIM 子目录没有 level.dat，单独打包出来不是一份完整存档；
     * 而存档根名（地图的 worldName）必须是归一后的 {@code world_nether}/{@code world_the_end}，
     * 否则解包时定位不到。</p>
     */
    @Test
    void recognisesLegacyNestedDimensionDirectories() {
        InstanceDimensionDir nether = InstanceDimensionDir.legacyNested("world", "DIM-1").orElseThrow();
        assertEquals("minecraft:nether", nether.dimension());
        assertEquals("world_nether", nether.dirName());
        assertEquals("world", nether.packPath());

        InstanceDimensionDir end = InstanceDimensionDir.legacyNested("world", "DIM1").orElseThrow();
        assertEquals("minecraft:the_end", end.dimension());
        assertEquals("world_the_end", end.dirName());
        assertEquals("world", end.packPath());

        // 不是 DIM 目录（region、data、level.dat 都不是维度）一律不认
        assertTrue(InstanceDimensionDir.legacyNested("world", "region").isEmpty());
        assertTrue(InstanceDimensionDir.legacyNested("world", "level.dat").isEmpty());
        assertTrue(InstanceDimensionDir.legacyNested("", "DIM-1").isEmpty());

        assertEquals("DIM-1", InstanceDimensionDir.legacyNestedDirName("minecraft:nether"));
        assertEquals("DIM1", InstanceDimensionDir.legacyNestedDirName("minecraft:the_end"));
        assertEquals(null, InstanceDimensionDir.legacyNestedDirName("minecraft:overworld"),
                "主世界没有嵌套目录：它就是父目录本身");
    }

    /** 新版的维度目录名不该被当成老版嵌套目录（两者都会出现在 world 下的列表里）。 */
    @Test
    void distinguishesModernDimensionDirectoriesFromLegacyOnes() {
        assertTrue(InstanceDimensionDir.isLegacyNestedDir("DIM-1"));
        assertTrue(InstanceDimensionDir.isLegacyNestedDir("dim1"));
        assertFalse(InstanceDimensionDir.isLegacyNestedDir("world_nether"));
        assertFalse(InstanceDimensionDir.isLegacyNestedDir("region"));
        assertFalse(InstanceDimensionDir.isLegacyNestedDir(null));
    }
}
