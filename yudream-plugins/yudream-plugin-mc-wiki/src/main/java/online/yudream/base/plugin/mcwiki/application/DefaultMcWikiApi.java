package online.yudream.base.plugin.mcwiki.application;

import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiAssetService;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiCatalogIndex;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiPublicationRepository;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiResourceRepository;

public final class DefaultMcWikiApi implements McWikiApi {
    private final WikiVersionService versions; private final WikiResourceRepository resources; private final WikiPublicationRepository publication; private final WikiAssetService assets; private final WikiCatalogIndex catalogs;
    public DefaultMcWikiApi(WikiVersionService versions, WikiResourceRepository resources, WikiPublicationRepository publication, WikiAssetService assets, WikiCatalogIndex catalogs){this.versions=versions;this.resources=resources;this.publication=publication;this.assets=assets;this.catalogs=catalogs;}
    public Optional<String> publishedVersion(){return publication.current();}
    public List<String> publishedVersions(){return publication.published();}
    public List<McVersionInfo> listVersions(boolean releaseOnly){return versions.list(1,200).records().stream().filter(v->!releaseOnly||"release".equals(v.type())).toList();}
    public Optional<McItemEntry> getItem(String version,String id){return resources.item(version,id);}
    /** 名称模糊匹配与总数走按版本内存索引，避免逐行 findById 回查与整表计数扫描。 */
    public Page<McItemEntry> searchItems(String version,String keyword,int page,int size){WikiCatalogIndex.VersionIndex index=catalogs.get(version);return new Page<>(index.search(keyword,page,size),index.count(keyword),page,size);}
    public List<McItemEntry> items(String version){return catalogs.get(version).items();}
    public List<McRecipe> recipes(String version){return catalogs.get(version).recipes();}
    public List<McRecipe> getRecipesProducing(String version,String id){return resources.recipesProducing(version,id);}
    public List<McRecipe> getRecipesUsing(String version,String id){return resources.recipesUsing(version,id);}
    public Optional<McMobEntry> getMob(String version,String id){return Optional.empty();}
    public Optional<byte[]> getTexture(String version,String kind,String path){return assets.read(version,kind,path);}
    public String getTextureCdnKey(String version,String kind,String path){return "textures/"+version+"/"+kind+"/"+path;}
}
