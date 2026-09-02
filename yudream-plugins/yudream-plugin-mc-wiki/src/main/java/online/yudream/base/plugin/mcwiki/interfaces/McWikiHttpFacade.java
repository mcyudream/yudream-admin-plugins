package online.yudream.base.plugin.mcwiki.interfaces;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import online.yudream.base.plugin.mcwiki.application.JobService;
import online.yudream.base.plugin.mcwiki.application.PublishedWikiQueryService;
import online.yudream.base.plugin.mcwiki.application.WikiVersionService;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;
import online.yudream.base.plugin.mcwiki.infrastructure.RenderAssetStore;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiCatalogIndex;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiPublicationRepository;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiResourceRepository;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;

public final class McWikiHttpFacade {
    private final WikiVersionService versions; private final JobService jobs; private final PublishedWikiQueryService query; private final WikiResourceRepository resources; private final WikiPublicationRepository publication; private final PluginFileStore files; private final RenderAssetStore renders; private final WikiCatalogIndex catalogs;
    public McWikiHttpFacade(WikiVersionService versions, JobService jobs, PublishedWikiQueryService query, WikiResourceRepository resources, WikiPublicationRepository publication, PluginFileStore files, RenderAssetStore renders, WikiCatalogIndex catalogs) { this.versions=versions; this.jobs=jobs; this.query=query; this.resources=resources; this.publication=publication; this.files=files; this.renders=renders; this.catalogs=catalogs; }
    public PluginHttpResponse refreshVersions() { return PluginHttpResponse.ok(Map.of("records", versions.refresh())); }
    /** 版本列表：关键字/类型/状态（imported|unimported|published）过滤后分页；计数取导入记录，不做逐版本全表扫描。 */
    public PluginHttpResponse versions(PluginHttpRequest request) {
        String keyword=param(request,"keyword"),type=param(request,"type"),status=param(request,"status");
        Set<String> imported=resources.importedVersions(); List<String> publishedList=publication.published();
        List<McWikiApi.McVersionInfo> filtered=versions.listFiltered(keyword,type).stream().filter(v->{
            boolean isImported=imported.contains(v.id()), isPublished=publishedList.contains(v.id());
            if("imported".equals(status))return isImported; if("unimported".equals(status))return !isImported; if("published".equals(status))return isPublished; return true;
        }).toList();
        int page=Math.max(number(request,"page",1),1),size=Math.min(Math.max(number(request,"size",50),1),200);
        int from=Math.min((page-1)*size,filtered.size());
        List<Map<String,Object>> records=filtered.stream().skip(from).limit(size).map(v->versionRow(v,imported,publishedList)).toList();
        return PluginHttpResponse.ok(Map.of("records",records,"total",filtered.size()));
    }
    /** 版本行附加导入状态：物品/配方计数取导入记录，发布状态取已发布集合。 */
    private Map<String,Object> versionRow(McWikiApi.McVersionInfo v,Set<String> imported,List<String> published){Map<String,Object> row=new LinkedHashMap<>();row.put("id",v.id());row.put("type",v.type());row.put("releaseTime",v.releaseTime());row.put("url",v.url());row.put("latest",v.latest());boolean isImported=imported.contains(v.id());row.put("imported",isImported);var record=isImported?resources.importRecord(v.id()):java.util.Optional.<Map<String,Object>>empty();row.put("items",record.map(m->((Number)m.getOrDefault("items",0)).longValue()).orElse(0L));row.put("recipes",record.map(m->((Number)m.getOrDefault("recipes",0)).longValue()).orElse(0L));row.put("textures",resources.textureCount(v.id()));row.put("published",published.contains(v.id()));return row;}
    public PluginHttpResponse importVersion(PluginHttpRequest request) { JobService.Job job=jobs.create(path(request,3)); return PluginHttpResponse.json(202,Map.of("jobId",job.jobId(),"streamId",job.streamId())); }
    /** 删除某版本已导入的物品、配方、贴图引用与遗留贴图文件；若该版本已发布则先解除发布。 */
    public PluginHttpResponse deleteVersionData(PluginHttpRequest request) { String version=path(request,3); List<String> texturePaths=resources.texturePaths(version); Map<String,Integer> removed=resources.deleteVersionData(version); catalogs.invalidate(version); int filesRemoved=0; for(String path:texturePaths){try{files.delete("textures/"+version+"/"+path);filesRemoved++;}catch(Exception ignored){}} publication.unpublish(version); Map<String,Object> result=new LinkedHashMap<>(removed); result.put("files",filesRemoved); result.put("unpublished",!publication.published().contains(version)); return PluginHttpResponse.ok(result); }
    public PluginHttpResponse publish(PluginHttpRequest request) { query.publish(path(request,3)); return PluginHttpResponse.ok(Map.of("published", true)); }
    public PluginHttpResponse unpublish(PluginHttpRequest request) { query.unpublish(path(request,3)); return PluginHttpResponse.ok(Map.of("published", false)); }
    /** 共享渲染资产元信息：来源 commit、对应 MC 版本、计数与更新时间；未更新过时 updated=false。 */
    public PluginHttpResponse renders() { Map<String,Object> result=new LinkedHashMap<>(); var meta=renders.meta(); result.put("updated",meta.isPresent()); meta.ifPresent(m->{result.put("commit",m.commit());result.put("gameVersion",m.gameVersion());result.put("items",m.items());result.put("entitiesFlat",m.entitiesFlat());result.put("entitiesIsometric",m.entitiesIsometric());result.put("updatedAt",m.updatedAt());}); return PluginHttpResponse.ok(result); }
    /** 一键更新渲染资产：创建后台任务，前端经任务日志弹窗跟进进度。 */
    public PluginHttpResponse updateRenders() { JobService.Job job=jobs.create(RenderAssetStore.JOB_VERSION); return PluginHttpResponse.json(202,Map.of("jobId",job.jobId(),"streamId",job.streamId())); }
    public PluginHttpResponse jobs(PluginHttpRequest request) { int page=number(request,"page",1),size=Math.min(number(request,"size",20),200); List<Map<String,Object>> records=jobs.list(page,size); return PluginHttpResponse.ok(Map.of("records",records,"total",jobs.count())); }
    public PluginHttpResponse job(PluginHttpRequest request) { String id=path(request,3); Map<String,Object> result=new LinkedHashMap<>(); result.put("job",jobs.get(id)); long after=number(request,"afterSeq",0); result.put("events",jobs.eventsAfter(jobs.get(id).streamId(),after)); return PluginHttpResponse.ok(result); }
    public PluginHttpResponse events(PluginHttpRequest request) { var stream=jobs.stream(path(request,3)); if(stream==null)return PluginHttpResponse.rawJson(404,Map.of("message","任务事件流不存在")); return new PluginHttpResponse(200,Map.of("Cache-Control","no-cache","Connection","keep-alive"),"text/event-stream",stream,false); }
    public PluginHttpResponse pause(PluginHttpRequest request) { return PluginHttpResponse.ok(jobs.pause(path(request,3))); }
    public PluginHttpResponse resume(PluginHttpRequest request) { return PluginHttpResponse.ok(jobs.resume(path(request,3))); }
    public PluginHttpResponse cancel(PluginHttpRequest request) { return PluginHttpResponse.ok(jobs.cancel(path(request,3))); }
    public PluginHttpResponse delete(PluginHttpRequest request) { jobs.delete(path(request,3)); return PluginHttpResponse.ok(Map.of("deleted",true)); }
    private int number(PluginHttpRequest r,String key,int fallback){try{String value=r.query().getOrDefault(key,List.of()).stream().findFirst().orElse(null);return Integer.parseInt(value);}catch(Exception e){return fallback;}}
    private String param(PluginHttpRequest r,String key){return r.query().getOrDefault(key,List.of()).stream().findFirst().orElse(null);}
    private String path(PluginHttpRequest r,int index){String[] value=(r.path()==null?"":r.path()).split("/");if(index>=value.length)throw new IllegalArgumentException("路径参数缺失");return URLDecoder.decode(value[index],StandardCharsets.UTF_8);}
}
