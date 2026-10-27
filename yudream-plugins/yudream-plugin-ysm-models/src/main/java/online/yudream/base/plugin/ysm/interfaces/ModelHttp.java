package online.yudream.base.plugin.ysm.interfaces;

import online.yudream.base.plugin.ysm.application.ModelService;
import online.yudream.base.plugin.ysm.infrastructure.AssetLinks;
import online.yudream.base.plugin.ysm.domain.ModelSettings;
import online.yudream.base.plugin.spi.http.*;
import online.yudream.ysm.model.*;
import com.google.gson.*;
import java.util.*;

/** HTTP boundary: principal-derived ownership, bounded inputs, and explicit server/download grants. */
public final class ModelHttp {
    private final ModelService models;private final AssetLinks links;
    public ModelHttp(ModelService models,AssetLinks links){this.models=models;this.links=links;}
    public PluginHttpResponse list(PluginHttpRequest r,int mode){return PluginHttpResponse.ok(mode==0?models.own(owner(r),number(r,"page",1),number(r,"size",12)):mode==1?models.catalog(number(r,"page",1),number(r,"size",12)):models.admin(number(r,"page",1),number(r,"size",12),query(r,"reviewStatus","ALL")));}
    public PluginHttpResponse settings(PluginHttpRequest r){return PluginHttpResponse.ok(models.settings());}
    public PluginHttpResponse saveSettings(PluginHttpRequest r){JsonObject b=body(r);return PluginHttpResponse.ok(models.settings(new ModelSettings(bool(b,"uploadEnabled"),bool(b,"reviewRequired"))));}
    public PluginHttpResponse review(PluginHttpRequest r){JsonObject b=body(r);return PluginHttpResponse.ok(models.review(segment(r.path(),2),text(b,"hash"),bool(b,"approved"),text(b,"note")));}
    public PluginHttpResponse players(PluginHttpRequest r){return PluginHttpResponse.ok(models.players(owner(r)));}
    public PluginHttpResponse upload(PluginHttpRequest r,boolean version){JsonObject b=body(r);String encoded=text(b,"base64");if(encoded.length()>ModelCompiler.MAX_UPLOAD*4L/3+8)throw new IllegalArgumentException("文件超过 16 MiB");return PluginHttpResponse.ok(models.upload(owner(r),version?segment(r.path(),2):null,text(b,"name"),Base64.getDecoder().decode(encoded)));}
    public PluginHttpResponse preview(PluginHttpRequest r,boolean catalog){var a=models.preview(catalog?"":owner(r),segment(r.path(),catalog?1:2),catalog);return PluginHttpResponse.ok(ModelAsset.JSON.fromJson(a.data(),Object.class));}
    public PluginHttpResponse adminPreview(PluginHttpRequest r){return PluginHttpResponse.ok(ModelAsset.JSON.fromJson(models.adminPreview(segment(r.path(),2)).data(),Object.class));}
    public PluginHttpResponse rename(PluginHttpRequest r){return PluginHttpResponse.ok(models.rename(owner(r),segment(r.path(),2),text(body(r),"name")));}
    public PluginHttpResponse manage(PluginHttpRequest r){JsonObject b=body(r);return PluginHttpResponse.ok(models.manage(segment(r.path(),2),b.get("enabled").getAsBoolean(),b.get("serverEnabled").getAsBoolean()));}
    public PluginHttpResponse delete(PluginHttpRequest r,boolean admin){models.delete(admin?"":owner(r),segment(r.path(),2),admin);return PluginHttpResponse.noContent();}
    public PluginHttpResponse equip(PluginHttpRequest r){JsonObject b=body(r);return PluginHttpResponse.ok(models.equip(owner(r),segment(r.path(),2),text(b,"modelId"),text(b,"texture")));}
    public PluginHttpResponse manifest(PluginHttpRequest r){if(!bridge(r))return PluginHttpResponse.json(403,Map.of("message","服务器凭据无效"));return PluginHttpResponse.rawJson(200,models.manifest(query(r,"uuid","")));}
    public PluginHttpResponse state(PluginHttpRequest r){if(!bridge(r))return PluginHttpResponse.json(403,Map.of("message","服务器凭据无效"));String players=query(r,"players","");return PluginHttpResponse.rawJson(200,models.state(players.isBlank()?List.of():Arrays.asList(players.split(","))));}
    public PluginHttpResponse download(PluginHttpRequest r){String hash=segment(r.path(),2);long expires;try{expires=Long.parseLong(query(r,"expires","0"));}catch(NumberFormatException e){expires=0;}
        if(!links.verify("bundle",hash,expires,query(r,"sig","")))return PluginHttpResponse.json(403,Map.of("message","下载链接已失效"));
        return new PluginHttpResponse(200,Map.of("Cache-Control","private, max-age=3600"),"application/json",models.download(hash),false);
    }
    private boolean bridge(PluginHttpRequest r){String token=r.headers()==null?"":r.headers().entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("X-Ysm-Bridge-Token")).flatMap(e->e.getValue().stream()).findFirst().orElse("");return links.bridge(token);}
    public static String owner(PluginHttpRequest r){if(r.principal()==null||r.principal().userId()==null)throw new IllegalArgumentException("请先登录");return String.valueOf(r.principal().userId());}
    private static JsonObject body(PluginHttpRequest r){if(r.body()==null||r.body().length()>23*1024*1024)throw new IllegalArgumentException("请求内容过大或为空");try{return JsonParser.parseString(r.body()).getAsJsonObject();}catch(RuntimeException e){throw new IllegalArgumentException("请求格式无效");}}
    private static String text(JsonObject b,String k){return b.has(k)&&!b.get(k).isJsonNull()?b.get(k).getAsString():"";}
    private static boolean bool(JsonObject b,String k){JsonElement v=b.get(k);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("设置字段 "+k+" 必须为布尔值");return v.getAsBoolean();}
    private static int number(PluginHttpRequest r,String k,int n){try{return Math.max(1,Math.min(100000,Integer.parseInt(query(r,k,""+n))));}catch(NumberFormatException e){return n;}}
    private static String query(PluginHttpRequest r,String k,String d){return r.query()==null||!r.query().containsKey(k)||r.query().get(k).isEmpty()?d:r.query().get(k).get(0);}
    private static String segment(String p,int n){String[] s=Arrays.stream(p.split("/")).filter(x->!x.isBlank()).toArray(String[]::new);if(n>=s.length)throw new IllegalArgumentException("路径无效");return s[n];}
}
