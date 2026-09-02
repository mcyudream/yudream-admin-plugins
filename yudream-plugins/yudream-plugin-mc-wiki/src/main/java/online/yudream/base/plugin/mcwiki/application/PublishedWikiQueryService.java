package online.yudream.base.plugin.mcwiki.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;
import online.yudream.base.plugin.mcwiki.infrastructure.RenderAssetStore;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiCatalogIndex;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiPublicationRepository;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiResourceRepository;

public final class PublishedWikiQueryService {
    private final WikiResourceRepository resources; private final WikiPublicationRepository publication; private final RenderAssetStore renders; private final WikiCatalogIndex catalogs;
    public PublishedWikiQueryService(WikiResourceRepository resources, WikiPublicationRepository publication, RenderAssetStore renders, WikiCatalogIndex catalogs){this.resources=resources;this.publication=publication;this.renders=renders;this.catalogs=catalogs;}
    public void publish(String version){publication.publish(version);}
    public void unpublish(String version){publication.unpublish(version);}
    public Optional<String> publishedVersion(){return publication.current();}
    /** 公开 wiki 元信息：未发布时 published=false 且计数为 0，前端据此展示空态而非报错。versions 为全部已发布版本，供版本选择器。计数走内存索引，不做整表扫描。 */
    public Map<String,Object> meta(){Optional<String> published=publication.current();Map<String,Object> meta=new LinkedHashMap<>();meta.put("published",published.isPresent());meta.put("version",published.orElse(null));meta.put("versions",publication.published());meta.put("items",published.map(v->catalogs.get(v).count(null)).orElse(0L));meta.put("recipes",published.map(v->(long)catalogs.get(v).recipes().size()).orElse(0L));return meta;}
    public McWikiApi.Page<Map<String,Object>> recipes(String version,String keyword,int page,int size){Optional<String> selected=select(version);if(selected.isEmpty())return empty(page,size);List<Map<String,Object>> rows=resources.recipes(selected.get(),page,size,keyword).stream().map(m->enrichRecipe(selected.get(),m)).toList();return new McWikiApi.Page<>(rows,resources.recipeCount(selected.get(),keyword),page,size);}
    /** 物品列表走按版本内存索引（模糊匹配与计数都在内存完成）；行字段与仓储列表行一致。 */
    public McWikiApi.Page<Map<String,Object>> items(String version,String keyword,int page,int size){Optional<String> selected=select(version);if(selected.isEmpty())return empty(page,size);WikiCatalogIndex.VersionIndex index=catalogs.get(selected.get());List<Map<String,Object>> rows=index.search(keyword,page,size).stream().map(PublishedWikiQueryService::itemRow).toList();return new McWikiApi.Page<>(rows,index.count(keyword),page,size);}
    private static Map<String,Object> itemRow(McWikiApi.McItemEntry item){Map<String,Object> row=new LinkedHashMap<>();row.put("version",item.version());row.put("namespacedId",item.namespacedId());row.put("kind",item.kind());row.put("nameEn",item.nameEn());row.put("nameZh",item.nameZh());row.put("tags",item.tags());row.put("textureKey",item.textureKey());row.put("diagnostic",item.diagnostic());return row;}
    /** 物品详情：条目本体加上产出/使用它的配方（配方带结果物品名称与贴图键）。 */
    public Optional<Map<String,Object>> itemDetail(String version,String id){Optional<String> selected=select(version);if(selected.isEmpty())return Optional.empty();String v=selected.get();return resources.item(v,id).map(item->{Map<String,Object> detail=new LinkedHashMap<>();detail.put("item",item);detail.put("producing",resources.recipesProducing(v,id).stream().map(r->enrichRecipe(v,r)).toList());detail.put("using",resources.recipesUsing(v,id).stream().map(r->enrichRecipe(v,r)).toList());return detail;});}
    private Map<String,Object> enrichRecipe(String version,Map<String,Object> row){Map<String,Object> out=new LinkedHashMap<>(row);resources.item(version,String.valueOf(row.get("resultId"))).ifPresent(item->{out.put("resultNameZh",item.nameZh());out.put("resultNameEn",item.nameEn());out.put("resultTextureKey",item.textureKey());out.put("resultKind",item.kind());});return out;}
    private Map<String,Object> enrichRecipe(String version,McWikiApi.McRecipe recipe){Map<String,Object> out=new LinkedHashMap<>();out.put("id",recipe.id());out.put("version",recipe.version());out.put("type",recipe.type());out.put("resultId",recipe.resultId());out.put("resultCount",recipe.resultCount());out.put("ingredients",recipe.ingredients());out.put("rawJson",recipe.rawJson());return enrichRecipe(version,out);}
    /** 物品渲染详情：渲染图名与可用性、遗留贴图键/图层（旧数据）、方块状态 JSON 原文（仅方块有）。 */
    public Optional<Map<String,Object>> itemTextures(String version,String id){Optional<String> selected=select(version);if(selected.isEmpty())return Optional.empty();String v=selected.get();return resources.itemDoc(v,id).map(doc->{Map<String,Object> out=new LinkedHashMap<>();String kind=String.valueOf(doc.getOrDefault("kind","item"));out.put("id",id);out.put("version",v);out.put("kind",kind);out.put("renderName",renders.renderName(id));out.put("renderAvailable",renders.render(id,kind).isPresent());out.put("textureKey",blankToNull(doc.get("textureKey")));out.put("iconLayers",strings(doc.get("iconLayers")));Map<String,String> faces=new LinkedHashMap<>();if(doc.get("faces") instanceof Map<?,?> map)map.forEach((k,val)->faces.put(String.valueOf(k),String.valueOf(val)));out.put("faces",faces);String path=id.contains(":")?id.substring(id.indexOf(':')+1):id;resources.blockstate(v,path).ifPresent(bs->out.put("blockstate",String.valueOf(bs.getOrDefault("rawJson",""))));return out;});}
    /** 解析请求版本：空或 current 表示当前默认已发布版本；指定版本必须在已发布集合内。未发布返回 empty。 */
    private Optional<String> select(String requested){List<String> published=publication.published();if(published.isEmpty())return Optional.empty();if(requested==null||requested.isBlank()||"current".equals(requested))return publication.current().or(()->Optional.of(published.getLast()));if(!published.contains(requested))throw new IllegalArgumentException("仅允许查询已发布版本: "+String.join(", ",published));return Optional.of(requested);}
    private List<String> strings(Object value){if(!(value instanceof List<?> list))return List.of();return list.stream().map(String::valueOf).toList();}
    private String blankToNull(Object value){String text=String.valueOf(value==null?"":value);return text.isBlank()?null:text;}
    private McWikiApi.Page<Map<String,Object>> empty(int page,int size){return new McWikiApi.Page<>(List.of(),0,page,size);}
}
