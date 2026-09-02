package online.yudream.base.plugin.mcwiki.infrastructure;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LangMergerTest {
    @Test void chineseNameOverridesEnglishAndNamespacedKeysAreNormalized() throws Exception {
        LangMerger merger=new LangMerger(new com.fasterxml.jackson.databind.ObjectMapper());
        var result=merger.merge("{\"item.minecraft.apple\":\"Apple\"}".getBytes(),"{\"item.minecraft.apple\":\"苹果\"}".getBytes());
        assertEquals("Apple",result.get("item.minecraft.apple").en()); assertEquals("苹果",result.get("item.minecraft.apple").zh()); assertEquals("minecraft:apple",LangMerger.namespaced("item.minecraft.apple"));
    }
    @Test void legacyTextLangIsModernizedToNamespacedKeys() throws Exception {
        LangMerger merger=new LangMerger(new com.fasterxml.jackson.databind.ObjectMapper());
        String en="# comment\ntile.dirt.name=Dirt\nitem.diamond.name=Diamond\nentity.CaveSpider.name=Cave Spider\ntile.cloth.black.name=Black Wool\n";
        String zh="tile.dirt.name=泥土\nitem.diamond.name=钻石\nentity.CaveSpider.name=洞穴蜘蛛\ntile.cloth.black.name=黑色羊毛\n";
        var result=merger.merge(en.getBytes(java.nio.charset.StandardCharsets.UTF_8),zh.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("泥土",result.get("block.minecraft.dirt").zh());
        assertEquals("钻石",result.get("item.minecraft.diamond").zh());
        assertEquals("洞穴蜘蛛",result.get("entity.minecraft.cave_spider").zh());
        assertEquals("黑色羊毛",result.get("block.minecraft.cloth").zh());
        assertNull(result.get("tile.dirt.name"));
        assertEquals("minecraft:cave_spider",LangMerger.namespaced("entity.minecraft.cave_spider"));
    }
}
