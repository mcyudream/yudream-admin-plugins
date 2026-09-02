package online.yudream.base.plugin.mcwiki.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;
import online.yudream.base.plugin.mcwiki.infrastructure.AssetIndexImporter;
import online.yudream.base.plugin.mcwiki.infrastructure.ClientJarExtractor;
import online.yudream.base.plugin.mcwiki.infrastructure.LangMerger;
import online.yudream.base.plugin.mcwiki.infrastructure.MojangClient;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiCatalogIndex;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiResourceRepository;

/**
 * 版本导入流水线：只导入名称（含中文）、配方、生物 id 与方块状态。
 * 物品渲染图由共享渲染资产库（RenderAssetStore）统一提供，各版本不再下载贴图。
 */
public final class WikiImportPipeline {
    private static final int PHASES = 5;
    private final MojangClient mojang; private final WikiVersionService versions; private final ClientJarExtractor extractor; private final WikiResourceRepository resources; private final AssetIndexImporter assets; private final WikiCatalogIndex catalogs;
    public WikiImportPipeline(MojangClient mojang, WikiVersionService versions, ClientJarExtractor extractor, WikiResourceRepository resources, AssetIndexImporter assets, WikiCatalogIndex catalogs){this.mojang=mojang;this.versions=versions;this.extractor=extractor;this.resources=resources;this.assets=assets;this.catalogs=catalogs;}
    public void run(String version, JobService.Control control){
        control.progress("VERSION_DETAILS",0,PHASES,"正在读取版本详情");
        McWikiApi.McVersionInfo info=versions.require(version); JsonNode details=mojang.versionDetails(info.url());
        String clientUrl=details.path("downloads").path("client").path("url").asText(null); if(clientUrl==null||clientUrl.isBlank())throw new IllegalStateException("版本详情没有客户端 JAR 下载地址");
        control.checkpoint(); control.progress("CLIENT_JAR",1,PHASES,"正在下载客户端 JAR");
        Path jar=null; try {
            jar=Files.createTempFile("mc-wiki-", ".jar"); Files.write(jar,mojang.download(clientUrl)); control.checkpoint();
            control.progress("ASSET_INDEX",2,PHASES,"正在下载中文语言文件");
            String assetIndexUrl=details.path("assetIndex").path("url").asText(null);
            byte[] zhLang=assetIndexUrl==null||assetIndexUrl.isBlank()?null:assets.importZhLang(assetIndexUrl,control);
            control.checkpoint(); control.progress("PARSE_JAR",3,PHASES,"正在解析语言、配方、标签和实体战利品"); ClientJarExtractor.Extraction data=extractor.extract(jar,zhLang);
            control.checkpoint(); control.progress("SAVE_RECORDS",4,PHASES,"正在写入物品、生物与配方清单");
            Map<String,String> kinds=new java.util.LinkedHashMap<>();
            data.names().forEach((key,name)->{String id=LangMerger.namespaced(key);if(id!=null)kinds.put(id,key.startsWith("block.minecraft.")?"block":key.startsWith("entity.minecraft.")?"entity":"item");});
            for(String entity:data.entityIds())kinds.putIfAbsent("minecraft:"+entity,"entity");
            for(String path:data.blockstates().keySet()){kinds.putIfAbsent("minecraft:"+path,"block");resources.saveBlockstate(version,path,data.blockstates().get(path));}
            int savedItems=0;
            for(Map.Entry<String,String> entry:kinds.entrySet()){String id=entry.getKey(),kind=entry.getValue();LangMerger.Name name=data.names().get(langKeyOf(id,kind));resources.saveItem(new McWikiApi.McItemEntry(version,id,kind,name==null?null:name.en(),name==null?null:name.zh(),List.of(),null,""));savedItems++;}
            int savedRecipes=0;
            for(ClientJarExtractor.RecipeRecord recipe:data.recipes()) if(recipe.resultId()!=null){resources.saveRecipe(new McWikiApi.McRecipe(recipe.id(),version,recipe.type(),recipe.resultId(),recipe.resultCount(),recipe.ingredients(),recipe.grid(),recipe.rawJson()));savedRecipes++;}
            resources.saveImport(version,savedItems,savedRecipes);
            catalogs.invalidate(version);
            for(String diagnostic:data.diagnostics())control.log("WARN",diagnostic); control.progress("DONE",PHASES,PHASES,"已导入 "+savedItems+" 个条目、 "+savedRecipes+" 个配方；诊断 "+data.diagnostics().size()+" 条");
        } catch(java.io.IOException ex){throw new IllegalStateException("导入临时文件处理失败",ex);} finally {if(jar!=null)try{Files.deleteIfExists(jar);}catch(Exception ignored){}}
    }
    /** 由 namespacedId 反推语言键（LangMerger 产出的 names 键为 kind.minecraft.path 形式）。 */
    private String langKeyOf(String id,String kind){String path=id.contains(":")?id.substring(id.indexOf(':')+1):id;return kind+".minecraft."+path;}
}
