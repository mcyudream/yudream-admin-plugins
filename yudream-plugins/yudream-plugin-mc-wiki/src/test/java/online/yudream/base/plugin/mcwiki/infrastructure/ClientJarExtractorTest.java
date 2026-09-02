package online.yudream.base.plugin.mcwiki.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientJarExtractorTest {
    @Test void extractsLanguageItemsAndRecipesWithoutModelBasedSkipping() throws Exception {
        Path jar=Files.createTempFile("mc-wiki-fixture", ".jar");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))){put(zip,"assets/minecraft/lang/en_us.json","{\"item.minecraft.apple\":\"Apple\",\"block.minecraft.stone\":\"Stone\"}");put(zip,"data/minecraft/recipes/apple.json","{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:stone\"}],\"result\":{\"id\":\"minecraft:apple\",\"count\":2}}");}
        ClientJarExtractor.Extraction result=new ClientJarExtractor(new ObjectMapper()).extract(jar);
        assertEquals("Apple",result.names().get("item.minecraft.apple").en());
        assertEquals(1,result.recipes().size());
        assertEquals("minecraft:apple",result.recipes().getFirst().resultId());
        assertEquals(2,result.recipes().getFirst().resultCount());
        assertTrue(result.diagnostics().isEmpty());
    }
    @Test void legacy1122LayoutReadsTextLangAndShapedKeys() throws Exception {
        Path jar=Files.createTempFile("mc-wiki-fixture-legacy", ".jar");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))){
            put(zip,"assets/minecraft/lang/en_us.lang","tile.log.oak.name=Oak Log\nitem.stick.name=Stick\n");
            put(zip,"assets/minecraft/recipes/acacia_boat.json","{\"type\":\"crafting_shaped\",\"pattern\":[\"# #\",\"###\"],\"key\":{\"#\":{\"item\":\"minecraft:planks\",\"data\":4}},\"result\":{\"item\":\"minecraft:acacia_boat\",\"count\":1}}");
            put(zip,"assets/minecraft/recipes/broken.json","{not json");
            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/items/apple.png"));zip.write(new byte[]{1,2,3});zip.closeEntry();
        }
        ClientJarExtractor.Extraction result=new ClientJarExtractor(new ObjectMapper()).extract(jar);
        assertEquals("Oak Log",result.names().get("block.minecraft.log").en());
        assertEquals(1,result.recipes().size());
        assertEquals("minecraft:crafting_shaped",result.recipes().getFirst().type());
        assertEquals("minecraft:acacia_boat",result.recipes().getFirst().resultId());
        assertTrue(result.recipes().getFirst().ingredients().contains("minecraft:planks"));
        assertEquals(1,result.diagnostics().size());
    }
    @Test void collectsBlockstatesAndEntityIds() throws Exception {
        Path jar=Files.createTempFile("mc-wiki-fixture-models", ".jar");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))){
            put(zip,"assets/minecraft/lang/en_us.json","{}");
            put(zip,"assets/minecraft/blockstates/acacia_button.json","{\"variants\":{\"face=wall,facing=east\":{\"model\":\"minecraft:block/acacia_button\"}}}");
            put(zip,"data/minecraft/loot_tables/entities/zombie.json","{\"pools\":[]}");
        }
        ClientJarExtractor.Extraction result=new ClientJarExtractor(new ObjectMapper()).extract(jar);
        assertTrue(result.blockstates().get("acacia_button").contains("variants"));
        assertTrue(result.entityIds().contains("zombie"));
    }
    @Test void shapedRecipeBuildsNineCellGridResolvingTags() throws Exception {
        Path jar=Files.createTempFile("mc-wiki-fixture-grid", ".jar");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))){
            put(zip,"assets/minecraft/lang/en_us.json","{}");
            put(zip,"data/minecraft/tags/items/planks.json","{\"replace\":false,\"values\":[\"minecraft:oak_planks\",\"minecraft:spruce_planks\"]}");
            put(zip,"data/minecraft/tags/items/logs.json","{\"replace\":false,\"values\":[\"minecraft:oak_log\",\"#minecraft:planks\"]}");
            // 有序：tag 原料取成员表首个（橡木木板），中心空格为 null
            put(zip,"data/minecraft/recipes/chest.json","{\"type\":\"minecraft:crafting_shaped\",\"key\":{\"#\":{\"tag\":\"minecraft:planks\"}},\"pattern\":[\"###\",\"# #\",\"###\"],\"result\":{\"id\":\"minecraft:chest\",\"count\":1}}");
            // 无序：备选数组取首个可解析项
            put(zip,"data/minecraft/recipes/torch.json","{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[[{\"tag\":\"minecraft:missing_tag\"},{\"item\":\"minecraft:coal\"}],{\"item\":\"minecraft:stick\"}],\"result\":{\"id\":\"minecraft:torch\",\"count\":4}}");
            // 单原料熔炼：只占第 1 格
            put(zip,"data/minecraft/recipes/glass.json","{\"type\":\"minecraft:smelting\",\"ingredient\":{\"item\":\"minecraft:sand\"},\"result\":{\"id\":\"minecraft:glass\",\"count\":1}}");
            // 嵌套 tag：logs 成员表含 planks tag，展开后取首个具体物品
            put(zip,"data/minecraft/recipes/oak_planks.json","{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"tag\":\"minecraft:logs\"}],\"result\":{\"id\":\"minecraft:oak_planks\",\"count\":4}}");
        }
        ClientJarExtractor.Extraction result=new ClientJarExtractor(new ObjectMapper()).extract(jar);
        assertTrue(result.diagnostics().isEmpty(),String.join(",",result.diagnostics()));
        ClientJarExtractor.RecipeRecord chest=result.recipes().stream().filter(r->r.id().equals("chest")).findFirst().orElseThrow();
        assertEquals(9,chest.grid().size());
        assertEquals("minecraft:oak_planks",chest.grid().get(0));
        assertNull(chest.grid().get(4));
        assertEquals("minecraft:oak_planks",chest.grid().get(8));
        ClientJarExtractor.RecipeRecord torch=result.recipes().stream().filter(r->r.id().equals("torch")).findFirst().orElseThrow();
        assertEquals("minecraft:coal",torch.grid().get(0));
        assertEquals("minecraft:stick",torch.grid().get(1));
        assertNull(torch.grid().get(2));
        ClientJarExtractor.RecipeRecord glass=result.recipes().stream().filter(r->r.id().equals("glass")).findFirst().orElseThrow();
        assertEquals("minecraft:sand",glass.grid().get(0));
        assertNull(glass.grid().get(1));
        ClientJarExtractor.RecipeRecord planks=result.recipes().stream().filter(r->r.id().equals("oak_planks")).findFirst().orElseThrow();
        assertEquals("minecraft:oak_log",planks.grid().get(0));
        assertNull(planks.grid().get(1));
        // 扁平原料清单仍保留 "#tag" 形式（检索/展示语义不变）
        assertTrue(chest.ingredients().contains("#minecraft:planks"));
    }
    private static void put(ZipOutputStream zip,String path,String content)throws Exception{zip.putNextEntry(new ZipEntry(path));zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
}
