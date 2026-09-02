package online.yudream.base.plugin.mcwiki.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McItemEntry;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McRecipe;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class WikiResourceRepository {
    // 宿主 MongoPluginDocumentStore 单页上限 200（MAX_PAGE_SIZE），请求更大会被静默截断导致误判读完
    private static final int SCAN_PAGE = 200;
    private final PluginDocumentStore documents;
    public WikiResourceRepository(PluginDocumentStore documents) { this.documents=documents; }
    /** 导入完成时写入导入记录，版本列表用它做导入状态/计数，避免逐版本全表扫描。 */
    public void saveImport(String version,int items,int recipes){Map<String,Object> m=new LinkedHashMap<>();m.put("version",version);m.put("items",items);m.put("recipes",recipes);m.put("importedAt",System.currentTimeMillis());documents.save("imports",version,m);}
    public Optional<Map<String,Object>> importRecord(String version){return documents.findById("imports",version);}
    public java.util.Set<String> importedVersions(){java.util.Set<String> out=new java.util.LinkedHashSet<>();int page=1;while(true){List<Map<String,Object>> chunk=documents.findAll("imports",page,SCAN_PAGE);chunk.forEach(m->out.add(String.valueOf(m.get("version"))));if(chunk.size()<SCAN_PAGE)return out;page++;}}
    public void saveItem(McItemEntry item){saveItem(item,List.of(),Map.of());}
    /** @param iconLayers 背包渲染图标图层（相对 textures 的 .png 路径，先底后顶）；@param faces 方块面贴图键→路径 */
    public void saveItem(McItemEntry item,List<String> iconLayers,Map<String,String> faces){Map<String,Object> m=new LinkedHashMap<>();m.put("version",item.version());m.put("namespacedId",item.namespacedId());m.put("kind",item.kind());m.put("nameEn",item.nameEn()==null?"":item.nameEn());m.put("nameZh",item.nameZh()==null?"":item.nameZh());m.put("tags",item.tags()==null?List.of():item.tags());m.put("textureKey",item.textureKey()==null?"":item.textureKey());m.put("iconLayers",iconLayers==null?List.of():iconLayers);m.put("faces",faces==null?Map.of():new LinkedHashMap<>(faces));m.put("diagnostic",item.diagnostic()==null?"":item.diagnostic());documents.save("items",item.version()+":"+item.namespacedId(),m);}
    public void saveBlockstate(String version,String blockPath,String rawJson){documents.save("blockstates",version+":"+blockPath,Map.of("version",version,"block",blockPath,"rawJson",rawJson));}
    public Optional<Map<String,Object>> blockstate(String version,String blockPath){return documents.findById("blockstates",version+":"+blockPath);}
    /** 物品原始文档（含 iconLayers/faces），供图标渲染与贴图详情使用。 */
    public Optional<Map<String,Object>> itemDoc(String version,String id){return documents.findById("items",version+":"+id);}
    /** 宿主 save 会用存储键覆写文档 "id" 字段，领域 id 必须存到独立 "recipeId" 字段才能原样读回。 */
    public void saveRecipe(McRecipe recipe){Map<String,Object> m=new LinkedHashMap<>();m.put("recipeId",recipe.id());m.put("version",recipe.version());m.put("type",recipe.type());m.put("resultId",recipe.resultId());m.put("resultCount",recipe.resultCount());m.put("ingredients",recipe.ingredients()==null?List.of():recipe.ingredients());m.put("grid",recipe.grid()==null?List.of():new ArrayList<>(recipe.grid()));m.put("rawJson",recipe.rawJson()==null?"":recipe.rawJson());documents.save("recipes",recipe.version()+":"+recipe.id(),m);}
    public void saveTextureRef(String version,String path){documents.save("textures",version+":"+path,Map.of("version",version,"path",path));}
    public List<Map<String,Object>> items(String version,int page,int size,String keyword){return page(filter(all("items",version),keyword,"namespacedId","nameZh","nameEn"),page,size);}
    public List<Map<String,Object>> recipes(String version,int page,int size,String keyword){return page(filter(all("recipes",version),keyword,"resultId","type","recipeId"),page,size);}
    public long itemCount(String version,String keyword){return filter(all("items",version),keyword,"namespacedId","nameZh","nameEn").size();}
    public long recipeCount(String version,String keyword){return filter(all("recipes",version),keyword,"resultId","type","recipeId").size();}
    public Optional<McItemEntry> item(String version,String id){return documents.findById("items",version+":"+id).map(this::item);}
    /** 定点查：resultId 等值匹配（宿主 Mongo 走 Criteria 索引查询）后在内存过滤版本，避免整表扫描。 */
    public List<McRecipe> recipesProducing(String version,String id){return collectByField("resultId",id,version,new LinkedHashMap<>()).values().stream().toList();}
    /** 定点查：ingredients 数组包含匹配（Mongo 等值作用于数组任一元素）；原料有物品 id 与 "#id" tag 两种历史形态，合并去重。 */
    public List<McRecipe> recipesUsing(String version,String id){Map<String,McRecipe> merged=collectByField("ingredients",id,version,new LinkedHashMap<>());collectByField("ingredients","#"+id,version,merged);return merged.values().stream().toList();}
    /** 版本全量物品/配方（索引构建用，约 200/页分页读取；调用方应缓存结果而非每次调用）。 */
    public List<McItemEntry> allItems(String version){return all("items",version).stream().map(this::item).toList();}
    public List<McRecipe> allRecipes(String version){return all("recipes",version).stream().map(this::recipe).toList();}
    private Map<String,McRecipe> collectByField(String field,Object value,String version,Map<String,McRecipe> out){int page=1;while(true){List<Map<String,Object>> chunk=documents.findByField("recipes",field,value,page,SCAN_PAGE);for(Map<String,Object> m:chunk){if(version.equals(String.valueOf(m.get("version")))){McRecipe r=recipe(m);out.putIfAbsent(r.id(),r);}}if(chunk.size()<SCAN_PAGE)return out;page++;}}
    /** 删除某版本的物品、配方、贴图引用、方块状态与导入记录，返回各集合删除数量。 */
    public Map<String,Integer> deleteVersionData(String version){int items=deleteAll("items",version),recipes=deleteAll("recipes",version),textures=deleteAll("textures",version),blockstates=deleteAll("blockstates",version);try{documents.delete("imports",version);}catch(RuntimeException ignored){}return Map.of("items",items,"recipes",recipes,"textures",textures,"blockstates",blockstates);}
    public List<String> texturePaths(String version){return all("textures",version).stream().map(m->String.valueOf(m.get("path"))).toList();}
    public long textureCount(String version){return all("textures",version).size();}
    /** 各集合文档的 "id" 字段都被宿主写为存储键（items=version:namespacedId、recipes=version:recipeId 等），直接用作删除键。 */
    private int deleteAll(String collection,String version){int removed=0;for(Map<String,Object> doc:all(collection,version)){documents.delete(collection,String.valueOf(doc.get("id")));removed++;}return removed;}
    private List<Map<String,Object>> all(String collection,String version){List<Map<String,Object>> out=new ArrayList<>();int page=1;while(true){List<Map<String,Object>> chunk=documents.findByField(collection,"version",version,page,SCAN_PAGE);out.addAll(chunk);if(chunk.size()<SCAN_PAGE)return out;page++;}}
    private List<Map<String,Object>> page(List<Map<String,Object>> source,int page,int size){int capped=Math.min(Math.max(size,1),200);return source.stream().skip((long)(Math.max(page,1)-1)*capped).limit(capped).toList();}
    private List<Map<String,Object>> filter(List<Map<String,Object>> source,String keyword,String... fields){if(keyword==null||keyword.isBlank())return source;String q=keyword.toLowerCase(java.util.Locale.ROOT);return source.stream().filter(m->{for(String f:fields)if(String.valueOf(m.getOrDefault(f,"")).toLowerCase(java.util.Locale.ROOT).contains(q))return true;return false;}).toList();}
    private McItemEntry item(Map<String,Object> m){return new McItemEntry(String.valueOf(m.get("version")),String.valueOf(m.get("namespacedId")),String.valueOf(m.get("kind")),String.valueOf(m.get("nameEn")),String.valueOf(m.get("nameZh")),strings(m.get("tags")),blankToNull(m.get("textureKey")),blankToNull(m.get("diagnostic")));}
    private McRecipe recipe(Map<String,Object> m){String version=String.valueOf(m.get("version"));Object recipeId=m.get("recipeId");String id=recipeId!=null?String.valueOf(recipeId):stripVersionPrefix(version,String.valueOf(m.get("id")));return new McRecipe(id,version,String.valueOf(m.get("type")),String.valueOf(m.get("resultId")),number(m.get("resultCount")),strings(m.get("ingredients")),grid(m.get("grid")),String.valueOf(m.getOrDefault("rawJson","")));}
    /** 旧文档没有 recipeId 字段，其 "id" 已被宿主覆写为存储键 version:id，剥掉前缀还原领域 id。 */
    private static String stripVersionPrefix(String version,String storageId){String prefix=version+":";return storageId!=null&&storageId.startsWith(prefix)?storageId.substring(prefix.length()):storageId;}
    /** grid 九格允许 null（空格），不能用 strings() 的 String::valueOf（会把 null 变成 "null"）。 */
    private List<String> grid(Object value){if(!(value instanceof List<?> list))return List.of();return list.stream().map(v->v==null?null:String.valueOf(v)).toList();}
    private List<String> strings(Object value){if(!(value instanceof List<?> list))return List.of();return list.stream().map(String::valueOf).toList();}
    private String blankToNull(Object value){String text=String.valueOf(value==null?"":value);return text.isBlank()?null:text;}
    private int number(Object value){return value instanceof Number n?n.intValue():Integer.parseInt(String.valueOf(value));}
}
