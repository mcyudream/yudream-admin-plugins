package online.yudream.base.plugin.mcwiki.interfaces;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import online.yudream.base.plugin.mcwiki.application.PublishedWikiQueryService;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiAssetService;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiIconRenderer;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public final class McWikiPublicController {
    private final PublishedWikiQueryService query; private final WikiAssetService assets; private final WikiIconRenderer icons; private final online.yudream.base.plugin.mcwiki.infrastructure.RenderAssetStore renders;
    public McWikiPublicController(PublishedWikiQueryService query,WikiAssetService assets,WikiIconRenderer icons,online.yudream.base.plugin.mcwiki.infrastructure.RenderAssetStore renders){this.query=query;this.assets=assets;this.icons=icons;this.renders=renders;}
    @PluginHttpEndpoint(method="GET",path="/public/meta")
    public PluginHttpResponse meta(){return PluginHttpResponse.ok(query.meta());}
    @PluginHttpEndpoint(method="GET",path="/public/recipes")
    public PluginHttpResponse recipes(PluginHttpRequest request){var page=query.recipes(first(request,"version"),first(request,"keyword"),number(request,"page",1),number(request,"size",24));return PluginHttpResponse.ok(Map.of("records",page.records(),"total",page.total()));}
    @PluginHttpEndpoint(method="GET",path="/public/items")
    public PluginHttpResponse items(PluginHttpRequest request){var page=query.items(first(request,"version"),first(request,"keyword"),number(request,"page",1),number(request,"size",24));return PluginHttpResponse.ok(Map.of("records",page.records(),"total",page.total()));}
    @PluginHttpEndpoint(method="GET",path="/public/items/{itemId}")
    public PluginHttpResponse itemDetail(PluginHttpRequest request){String id=path(request,3);return query.itemDetail(first(request,"version"),id).<PluginHttpResponse>map(PluginHttpResponse::ok).orElseGet(()->PluginHttpResponse.rawJson(404,Map.of("message","物品不存在或尚无已发布版本")));}
    /** 物品贴图详情：背包图标图层、方块面贴图和方块状态 JSON，供详情抽屉预览与自定义尺寸下载。 */
    @PluginHttpEndpoint(method="GET",path="/public/items/{itemId}/textures")
    public PluginHttpResponse itemTextures(PluginHttpRequest request){String id=path(request,3);return query.itemTextures(first(request,"version"),id).<PluginHttpResponse>map(PluginHttpResponse::ok).orElseGet(()->PluginHttpResponse.rawJson(404,Map.of("message","物品不存在或尚无已发布版本")));}
    /** 背包渲染图标：优先共享渲染资产库的 3D 渲染图（双线性缩放）；无渲染图时回退遗留图层合成/单张贴图（最近邻）。 */
    @PluginHttpEndpoint(method="GET",path="/public/icon")
    public PluginHttpResponse icon(PluginHttpRequest request){
        String id=required(first(request,"id"),"id"); int size=icons.clampSize(sizeParam(request));
        Optional<Map<String,Object>> textures=query.itemTextures(first(request,"version"),id);
        if(textures.isEmpty())return PluginHttpResponse.rawJson(404,Map.of("message","物品不存在或尚无已发布版本"));
        String kind=String.valueOf(textures.get().getOrDefault("kind","item"));
        Optional<byte[]> rendered=renders.render(id,kind).flatMap(bytes->icons.scaleRender(bytes,size));
        if(rendered.isPresent())return rendered.map(bytes->new PluginHttpResponse(200,Map.of("Cache-Control","public, max-age=300"),"image/png",bytes,false)).orElseThrow();
        String version=String.valueOf(textures.get().get("version"));
        @SuppressWarnings("unchecked") List<String> layers=(List<String>)textures.get().getOrDefault("iconLayers",List.of());
        Optional<byte[]> png=layers.isEmpty()?Optional.empty():icons.renderLayers(version,layers,size);
        if(png.isEmpty()){Object key=textures.get().get("textureKey");if(key==null)return PluginHttpResponse.rawJson(404,Map.of("message","该物品没有可用贴图，请先在版本管理中一键更新渲染资产"));png=icons.renderSingle(version,String.valueOf(key),size);}
        return png.map(bytes->new PluginHttpResponse(200,Map.of("Cache-Control","public, max-age=300"),"image/png",bytes,false)).orElseGet(()->PluginHttpResponse.rawJson(404,Map.of("message","贴图文件缺失")));
    }
    /** 原始贴图文件：version 可省略（默认已发布版本）；size 传像素尺寸时按最近邻缩放输出。 */
    @PluginHttpEndpoint(method="GET",path="/public/assets/file")
    public PluginHttpResponse asset(PluginHttpRequest request){
        String kind=required(first(request,"kind"),"kind"),path=required(first(request,"path"),"path");
        String version=first(request,"version");
        if(version==null||version.isBlank()){version=query.publishedVersion().orElse(null);if(version==null)return PluginHttpResponse.rawJson(404,Map.of("message","尚无已发布版本"));}
        Integer size=sizeParam(request);
        if(size!=null){final String v=version;return icons.renderSingle(v,kind+"/"+path,icons.clampSize(size)).map(bytes->new PluginHttpResponse(200,Map.of("Cache-Control","public, max-age=86400"),"image/png",bytes,false)).orElseGet(()->PluginHttpResponse.rawJson(404,Map.of("message","素材不存在")));}
        return assets.read(version,kind,path).map(bytes->new PluginHttpResponse(200,Map.of("Cache-Control","public, max-age=86400"),"image/png",bytes,false)).orElseGet(()->PluginHttpResponse.rawJson(404,Map.of("message","素材不存在")));
    }
    private Integer sizeParam(PluginHttpRequest r){String raw=first(r,"size");if(raw==null||raw.isBlank())return null;try{return Integer.valueOf(raw);}catch(NumberFormatException e){return null;}}
    private String first(PluginHttpRequest r,String key){List<String> v=r.query().get(key);return v==null||v.isEmpty()?null:v.getFirst();}
    private int number(PluginHttpRequest r,String key,int fallback){try{return Integer.parseInt(first(r,key));}catch(Exception e){return fallback;}}
    private String required(String v,String key){if(v==null||v.isBlank())throw new IllegalArgumentException(key+" 不能为空");return v;}
    private String path(PluginHttpRequest r,int index){String[] value=(r.path()==null?"":r.path()).split("/");if(index>=value.length)throw new IllegalArgumentException("路径参数缺失");return URLDecoder.decode(value[index],StandardCharsets.UTF_8);}
}
