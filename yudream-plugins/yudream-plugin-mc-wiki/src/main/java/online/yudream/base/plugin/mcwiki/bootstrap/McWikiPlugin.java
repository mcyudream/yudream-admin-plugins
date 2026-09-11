package online.yudream.base.plugin.mcwiki.bootstrap;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import online.yudream.base.plugin.mcwiki.application.JobService;
import online.yudream.base.plugin.mcwiki.application.PublishedWikiQueryService;
import online.yudream.base.plugin.mcwiki.application.WikiImportPipeline;
import online.yudream.base.plugin.mcwiki.application.WikiVersionService;
import online.yudream.base.plugin.mcwiki.infrastructure.AssetIndexImporter;
import online.yudream.base.plugin.mcwiki.infrastructure.ClientJarExtractor;
import online.yudream.base.plugin.mcwiki.infrastructure.RenderAssetStore;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiResourceRepository;
import online.yudream.base.plugin.mcwiki.infrastructure.MojangClient;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiAssetService;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiCatalogIndex;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiPublicationRepository;
import online.yudream.base.plugin.mcwiki.infrastructure.WikiVersionRepository;
import online.yudream.base.plugin.mcwiki.interfaces.McWikiAdminController;
import online.yudream.base.plugin.mcwiki.interfaces.McWikiHttpFacade;
import online.yudream.base.plugin.mcwiki.interfaces.McWikiPublicController;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

@PluginSpec(code = McWikiPlugin.CODE, name = "MC 百科", version = "2.0.1", description = "Minecraft 版本资源、物品、生物与配方百科")
@PluginPermissions({
        @PluginPermission(code = McWikiPlugin.VIEW_PERMISSION, name = "查看 MC 百科", module = "平台插件", description = "查看公开 Minecraft 资源"),
        @PluginPermission(code = McWikiPlugin.MANAGE_PERMISSION, name = "管理 MC 百科", module = "平台插件", description = "管理版本、导入任务和发布资源")
})
@PluginFrontend(moduleName = "mc-wiki", menuTitle = "MC 百科", menuIcon = "i-ri:gamepad-line", menuSort = 70, styles = {"style.css"}, routes = {
        @PluginRoute(path = "/platform/plugins/mc-wiki/catalog/crafting-recipes", name = "mc-wiki-catalog-crafting-recipes", title = "合成配方", icon = "i-ri:hammer-line", component = "mc-wiki/CraftingRecipes", permission = McWikiPlugin.VIEW_PERMISSION, sort = 10),
        @PluginRoute(path = "/platform/plugins/mc-wiki/catalog/items", name = "mc-wiki-catalog-items", title = "物品图鉴", icon = "i-ri:archive-line", component = "mc-wiki/Items", permission = McWikiPlugin.VIEW_PERMISSION, sort = 15),
        @PluginRoute(path = "/platform/plugins/mc-wiki/admin/versions", name = "mc-wiki-versions", title = "百科版本", icon = "i-ri:git-branch-line", component = "mc-wiki/Versions", permission = McWikiPlugin.MANAGE_PERMISSION, sort = 20),
        @PluginRoute(path = "/platform/plugins/mc-wiki/admin/jobs", name = "mc-wiki-jobs", title = "导入任务", icon = "i-ri:terminal-box-line", component = "mc-wiki/Jobs", permission = McWikiPlugin.MANAGE_PERMISSION, sort = 30),
        @PluginRoute(path = "/encyclopedia", name = "mc-wiki-encyclopedia", title = "百科", icon = "i-ri:book-open-line", component = "mc-wiki/PublicEncyclopedia", hideInMenu = true, publicAccess = true, siteNav = true)
})
public final class McWikiPlugin implements YuDreamPlugin {
    public static final String CODE = "mc-wiki";
    public static final String VIEW_PERMISSION = "plugin:mc-wiki:view";
    public static final String MANAGE_PERMISSION = "plugin:mc-wiki:manage";

    @Override public void onEnable(PluginContext context) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ThreadPoolExecutor jobs = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), Thread.ofVirtual().name("mc-wiki-job-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        context.onDispose(http::close); context.onDispose(jobs::shutdownNow);
        ObjectMapper mapper = new ObjectMapper();
        MojangClient mojang = new MojangClient(http, mapper);
        WikiVersionService versionService = new WikiVersionService(mojang, new WikiVersionRepository(context.documents()));
        WikiResourceRepository resources = new WikiResourceRepository(context.documents());
        WikiCatalogIndex catalogs = new WikiCatalogIndex(resources);
        WikiAssetService assetService = new WikiAssetService(context.files());
        RenderAssetStore renders = new RenderAssetStore(context.files(), context.documents(), mojang::download);
        WikiImportPipeline pipeline = new WikiImportPipeline(mojang, versionService, new ClientJarExtractor(mapper), resources, new AssetIndexImporter(mapper, mojang), catalogs);
        WikiPublicationRepository publication = new WikiPublicationRepository(context.documents());
        PublishedWikiQueryService query = new PublishedWikiQueryService(resources, publication, renders, catalogs);
        JobService jobService = new JobService(context.documents(), jobs, (version, control) -> { if (RenderAssetStore.JOB_VERSION.equals(version)) renders.update(control); else pipeline.run(version, control); });
        McWikiHttpFacade facade = new McWikiHttpFacade(versionService, jobService, query, resources, publication, context.files(), renders, catalogs);
        context.exposeService(online.yudream.base.plugin.mcwiki.api.McWikiApi.class, new online.yudream.base.plugin.mcwiki.application.DefaultMcWikiApi(versionService, resources, publication, assetService, catalogs, renders));
        context.registerHttpController(new McWikiAdminController(facade));
        context.registerHttpController(new McWikiPublicController(query, assetService, new online.yudream.base.plugin.mcwiki.infrastructure.WikiIconRenderer(assetService), renders));
    }
}
