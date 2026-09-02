package online.yudream.base.plugin.mcwiki.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import online.yudream.base.plugin.mcwiki.infrastructure.LangMerger.Name;

/**
 * 解析 Minecraft 客户端/服务端 JAR。兼容三代内部结构：
 * 1.12.2 及更早的 assets/minecraft/recipes 与 .lang 文本语言文件；
 * 1.13~1.20.5 的 data/minecraft/recipes、tags/items、loot_tables（复数目录）与 JSON 语言文件；
 * 1.21 起的单数目录 data/minecraft/recipe、tags/item、loot_table。
 * 注意 1.13 起 data/（配方、标签、战利品表）只存在于服务端 JAR，客户端 JAR 仅含 assets/
 * （语言、方块状态、模型），因此配方类数据必须通过双 JAR 提取（extract(client, server, zh)）。
 * 物品渲染图由共享渲染资产库（RenderAssetStore）提供，JAR 内贴图与模型不再提取。
 * 配方除扁平原料清单外还会预计算 3x3 九宫格 grid（null 为空格）：有序配方按 pattern/key 摆放，
 * 无序与单原料配方顺序填充；tag 与备选数组解析为具体物品（tag 取成员表首个，与旧 mcdata 数据语义一致）。
 */
public final class ClientJarExtractor {
    public record Extraction(Map<String,Name> names, List<RecipeRecord> recipes, Set<String> tags, Set<String> entityIds, Map<String,String> blockstates, List<String> diagnostics) {}
    public record RecipeRecord(String id, String type, String resultId, int resultCount, List<String> ingredients, List<String> grid, String rawJson) {}
    // 1.21 起 data 目录单数化（recipe/、tags/item/、loot_table/），此前为复数（recipes/、tags/items/、loot_tables/）
    private static final String ITEM_TAG_PREFIX="data/minecraft/tags/items/";
    private static final String ITEM_TAG_PREFIX_121="data/minecraft/tags/item/";
    private static final String BLOCK_TAG_PREFIX="data/minecraft/tags/blocks/";
    private static final String BLOCK_TAG_PREFIX_121="data/minecraft/tags/block/";
    private final ObjectMapper mapper; private final LangMerger langs;
    public ClientJarExtractor(ObjectMapper mapper) { this.mapper=mapper; this.langs=new LangMerger(mapper); }
    public Extraction extract(java.nio.file.Path jar) { return extract(jar, null); }
    public Extraction extract(java.nio.file.Path jar, byte[] externalZh) { return scan(jar, externalZh, "客户端 JAR"); }
    /**
     * 双 JAR 提取：语言与方块状态取自客户端 JAR，配方/标签/实体战利品优先取自服务端 JAR
     * （1.13+ 客户端 JAR 没有 data/ 目录）；服务端 JAR 为空或缺 data/ 时保留客户端结果，
     * 兼容 1.12.2 及更早只有客户端数据布局的版本。
     */
    public Extraction extract(java.nio.file.Path clientJar, java.nio.file.Path serverJar, byte[] externalZh) {
        Extraction client=scan(clientJar,externalZh,"客户端 JAR");
        if(serverJar==null)return client;
        Extraction server=scan(serverJar,null,"服务端 JAR");
        Map<String,RecipeRecord> recipes=new LinkedHashMap<>();
        for(RecipeRecord recipe:client.recipes())recipes.put(recipe.id(),recipe);
        for(RecipeRecord recipe:server.recipes())recipes.put(recipe.id(),recipe);
        Set<String> tags=new LinkedHashSet<>(client.tags()); tags.addAll(server.tags());
        Set<String> entities=new LinkedHashSet<>(client.entityIds()); entities.addAll(server.entityIds());
        List<String> diagnostics=new ArrayList<>(client.diagnostics()); diagnostics.addAll(server.diagnostics());
        return new Extraction(client.names(),List.copyOf(recipes.values()),Set.copyOf(tags),Set.copyOf(entities),client.blockstates(),List.copyOf(diagnostics));
    }
    private Extraction scan(java.nio.file.Path jar, byte[] externalZh, String label) {
        try(ZipFile zip=new ZipFile(jar.toFile())) {
            byte[] en=first(zip,"assets/minecraft/lang/en_us.json","assets/minecraft/lang/en_us.lang");
            byte[] zh=externalZh==null?first(zip,"assets/minecraft/lang/zh_cn.json","assets/minecraft/lang/zh_cn.lang"):externalZh;
            Map<String,Name> names=langs.merge(en,zh); List<RecipeRecord> recipes=new ArrayList<>(); Set<String> tags=new LinkedHashSet<>(), entities=new LinkedHashSet<>(); List<String> diagnostics=new ArrayList<>(); Map<String,String> blockstates=new LinkedHashMap<>();
            // 配方 grid 需要 tag 成员表，先在主扫描前单独过一遍物品 tag 文件（兼容 1.21 起的单数目录）
            Map<String,List<String>> tagValues=new LinkedHashMap<>();
            zip.stream().filter(e->!e.isDirectory()).forEach(entry->{String path=entry.getName();
                if(path.endsWith(".json")&&(path.startsWith(ITEM_TAG_PREFIX)||path.startsWith(ITEM_TAG_PREFIX_121))) try { int cut=path.startsWith(ITEM_TAG_PREFIX)?ITEM_TAG_PREFIX.length():ITEM_TAG_PREFIX_121.length(); tagValues.put("minecraft:"+path.substring(cut,path.length()-5),tagValues(read(zip,path))); } catch(Exception ex){ diagnostics.add(path+": "+ex.getMessage()); }});
            Map<String,List<String>> tagMembers=new LinkedHashMap<>();
            for(String tag:tagValues.keySet()) tagMembers.put(tag,resolveTag(tag,tagValues,new LinkedHashSet<>()));
            Map<String,List<String>> resolvedTags=Map.copyOf(tagMembers);
            zip.stream().filter(e->!e.isDirectory()).forEach(entry->{String path=entry.getName(); try {
                if(path.endsWith(".json")&&(path.startsWith("data/minecraft/recipes/")||path.startsWith("data/minecraft/recipe/")||path.startsWith("assets/minecraft/recipes/"))) recipes.add(recipe(path,read(zip,path),resolvedTags));
                else if(path.startsWith(ITEM_TAG_PREFIX)||path.startsWith(ITEM_TAG_PREFIX_121)||path.startsWith(BLOCK_TAG_PREFIX)||path.startsWith(BLOCK_TAG_PREFIX_121)) tags.add(canonicalTagId(path));
                else if(path.endsWith(".json")&&(path.startsWith("data/minecraft/loot_tables/entities/")||path.startsWith("data/minecraft/loot_table/entities/")||path.startsWith("assets/minecraft/loot_tables/entities/"))) entities.add(path.substring(path.lastIndexOf('/')+1,path.length()-5));
                else if(path.endsWith(".json")&&path.startsWith("assets/minecraft/blockstates/")) blockstates.put(path.substring("assets/minecraft/blockstates/".length(),path.length()-5),new String(read(zip,path),java.nio.charset.StandardCharsets.UTF_8));
            } catch(Exception ex){ diagnostics.add(path+": "+ex.getMessage()); }});
            return new Extraction(names,List.copyOf(recipes),Set.copyOf(tags),Set.copyOf(entities),Map.copyOf(blockstates),List.copyOf(diagnostics));
        } catch(IOException ex){throw new IllegalArgumentException(label+" 读取失败",ex);}
    }
    private RecipeRecord recipe(String path,byte[] bytes,Map<String,List<String>> tagMembers)throws IOException {
        JsonNode node=mapper.readTree(bytes); String result=null; int count=1; JsonNode out=node.path("result");
        if(out.isTextual())result=out.asText(); else {result=out.path("id").isTextual()?out.path("id").asText():out.path("item").asText(null);count=Math.max(1,out.path("count").asInt(1));}
        List<String> ingredients=new ArrayList<>();
        node.path("ingredients").forEach(v->ingredients.add(ingredient(v)));
        if(node.has("ingredient")) ingredients.add(ingredient(node.get("ingredient")));
        node.path("key").fields().forEachRemaining(e->ingredients.add(ingredient(e.getValue())));
        return new RecipeRecord(path.substring(path.lastIndexOf('/')+1,path.length()-5),type(node),normalize(result),count,ingredients.stream().filter(java.util.Objects::nonNull).distinct().toList(),grid(node,tagMembers),new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
    }
    /** 3x3 九宫格（行优先、靠左上、null 为空格）：有序配方按 pattern/key 落位，无序/单原料/锻造类顺序填充。 */
    private List<String> grid(JsonNode node,Map<String,List<String>> tagMembers){
        String[] cells=new String[9];
        JsonNode pattern=node.path("pattern");
        if(pattern.isArray()&&!pattern.isEmpty()){
            JsonNode key=node.path("key"); int row=0;
            for(JsonNode rowNode:pattern){ if(row>=3)break; String text=rowNode.asText("");
                for(int col=0;col<Math.min(text.length(),3);col++){ char symbol=text.charAt(col); if(symbol==' ')continue; cells[row*3+col]=gridIngredient(key.get(String.valueOf(symbol)),tagMembers); }
                row++; }
        } else {
            List<JsonNode> flat=new ArrayList<>();
            if(node.has("template")||node.has("base")||node.has("addition")) for(String field:List.of("template","base","addition")) { if(node.has(field)) flat.add(node.get(field)); }
            else { node.path("ingredients").forEach(flat::add); if(node.has("ingredient")) flat.add(node.get("ingredient")); }
            int slot=0; for(JsonNode value:flat){ if(slot>=9)break; cells[slot++]=gridIngredient(value,tagMembers); }
        }
        return Arrays.asList(cells);
    }
    /** 配方落格原料解析为具体物品 id；tag 取成员表首个，备选取首个可解析项，解析不了按空格处理。 */
    private String gridIngredient(JsonNode node,Map<String,List<String>> tagMembers){
        if(node==null||node.isNull())return null;
        if(node.isTextual())return normalize(node.asText());
        if(node.has("item"))return normalize(node.path("item").asText());
        if(node.has("tag")){List<String> members=tagMembers.get(normalize(node.path("tag").asText()));return members==null||members.isEmpty()?null:members.getFirst();}
        if(node.isArray()){for(JsonNode option:node){String resolved=gridIngredient(option,tagMembers);if(resolved!=null)return resolved;}}
        return null;
    }
    private List<String> tagValues(byte[] bytes)throws IOException {
        List<String> values=new ArrayList<>();
        for(JsonNode value:mapper.readTree(bytes).path("values")) if(value.isTextual()) values.add(value.asText());
        return values;
    }
    /** tag id 统一为复数规范形式（items/planks、blocks/logs），1.21 的单数目录与旧版输出保持一致。 */
    private String canonicalTagId(String path){
        String relative=path.substring(path.indexOf("tags/")+5,path.length()-5);
        if(relative.startsWith("item/"))return "items/"+relative.substring("item/".length());
        if(relative.startsWith("block/"))return "blocks/"+relative.substring("block/".length());
        return relative;
    }
    /** 展开 tag 成员（支持嵌套 "#tag"，带环保护），保持文件声明顺序。 */
    private List<String> resolveTag(String tag,Map<String,List<String>> raw,Set<String> visiting){
        if(!visiting.add(tag))return List.of();
        List<String> members=new ArrayList<>();
        for(String value:raw.getOrDefault(tag,List.of())){
            if(value.startsWith("#"))members.addAll(resolveTag(normalize(value.substring(1)),raw,visiting));
            else members.add(normalize(value));
        }
        visiting.remove(tag);
        return members;
    }
    private String type(JsonNode node){String type=node.path("type").asText(null);if(type==null||type.isBlank())type=node.has("pattern")?"crafting_shaped":"crafting_shapeless";return type.contains(":")?type:"minecraft:"+type;}
    private String ingredient(JsonNode node){if(node==null||node.isNull())return null;if(node.isTextual())return normalize(node.asText());if(node.has("item"))return normalize(node.path("item").asText());if(node.has("tag"))return "#"+normalize(node.path("tag").asText());if(node.has("ore"))return "ore:"+node.path("ore").asText();if(node.isArray()&&!node.isEmpty())return ingredient(node.get(0));return null;}
    private String normalize(String value){if(value==null||value.isBlank())return null;return value.contains(":")?value:"minecraft:"+value;}
    private byte[] first(ZipFile zip,String... names)throws IOException{for(String name:names){byte[] data=read(zip,name);if(data!=null)return data;}return null;}
    private byte[] read(ZipFile zip,String name)throws IOException{ZipEntry e=zip.getEntry(name);if(e==null)return null;try(InputStream in=zip.getInputStream(e)){return in.readAllBytes();}}
}
