package online.yudream.base.plugin.material.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.material.application.AdminMaterialService;
import online.yudream.base.plugin.material.application.CategoryService;
import online.yudream.base.plugin.material.application.FolderImportService;
import online.yudream.base.plugin.material.application.MaterialService;
import online.yudream.base.plugin.material.application.PreviewService;
import online.yudream.base.plugin.material.application.ShareService;
import online.yudream.base.plugin.material.infrastructure.CategoryRepository;
import online.yudream.base.plugin.material.infrastructure.JsonSupport;
import online.yudream.base.plugin.material.infrastructure.MaterialFileStorage;
import online.yudream.base.plugin.material.infrastructure.MaterialRepository;
import online.yudream.base.plugin.material.infrastructure.MaterialVersionRepository;
import online.yudream.base.plugin.material.infrastructure.PlatformFileIntake;
import online.yudream.base.plugin.material.infrastructure.ShareRepository;
import online.yudream.base.plugin.material.interfaces.MaterialAdminController;
import online.yudream.base.plugin.material.interfaces.MaterialMeController;
import online.yudream.base.plugin.material.interfaces.SharePublicController;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

@PluginSpec(code = MaterialPlugin.CODE, name = "电子物料库", version = MaterialPlugin.VERSION,
        description = "电子物料库：多格式物料上传、在线预览（平台 kkFileView 能力）、下载与版本管理回溯")
@PluginPermissions({
        @PluginPermission(code = MaterialPlugin.VIEW_PERMISSION, name = "使用物料库", module = "电子物料库",
                description = "浏览、上传、预览、下载和维护自己的物料"),
        @PluginPermission(code = MaterialPlugin.MANAGE_PERMISSION, name = "管理物料库", module = "电子物料库",
                description = "跨用户物料管理与分类维护")
})
@PluginFrontend(moduleName = "material", menuTitle = "物料库", menuIcon = "i-ri:folder-image-line", menuSort = 72, styles = {"style.css"}, routes = {
        @PluginRoute(path = "/platform/plugins/material", name = "platform-plugin-material-list", title = "物料库",
                icon = "i-ri:folder-image-line", component = "material/Library", permission = MaterialPlugin.VIEW_PERMISSION, sort = 10),
        @PluginRoute(path = "/platform/plugins/material/detail", name = "platform-plugin-material-detail", title = "物料详情",
                component = "material/Detail", permission = MaterialPlugin.VIEW_PERMISSION, hideInMenu = true),
        @PluginRoute(path = "/platform/plugins/material/admin", name = "platform-plugin-material-admin", title = "物料管理",
                icon = "i-ri:archive-stack-line", component = "material/Admin", permission = MaterialPlugin.MANAGE_PERMISSION, sort = 90),
        @PluginRoute(path = "/platform/plugins/material/admin/categories", name = "platform-plugin-material-admin-categories", title = "物料分类",
                icon = "i-ri:price-tag-3-line", component = "material/Categories", permission = MaterialPlugin.MANAGE_PERMISSION, sort = 91)
})
public final class MaterialPlugin implements YuDreamPlugin {
    public static final String CODE = "material";
    public static final String VERSION = "1.6.0";
    public static final String VIEW_PERMISSION = "plugin:material:view";
    public static final String MANAGE_PERMISSION = "plugin:material:manage";

    @Override
    public void onEnable(PluginContext context) {
        ObjectMapper mapper = new ObjectMapper();
        JsonSupport json = new JsonSupport(mapper);

        MaterialRepository materials = new MaterialRepository(context.documents());
        MaterialVersionRepository versions = new MaterialVersionRepository(context.documents());
        CategoryRepository categories = new CategoryRepository(context.documents());
        ShareRepository shares = new ShareRepository(context.documents());

        MaterialFileStorage storage = new MaterialFileStorage(context.files());
        PlatformFileIntake intake = new PlatformFileIntake(context.framework());

        MaterialService materialService = new MaterialService(materials, versions, categories, storage, intake, shares, context.framework());
        AdminMaterialService adminService = new AdminMaterialService(materialService, materials);
        CategoryService categoryService = new CategoryService(categories, materials);
        FolderImportService folderImportService = new FolderImportService(materialService, categoryService);
        PreviewService previewService = new PreviewService(context.framework(), CODE);
        ShareService shareService = new ShareService(shares, materialService, context.framework());

        context.registerHttpController(new MaterialMeController(materialService, categoryService, folderImportService, previewService, shareService, json));
        context.registerHttpController(new MaterialAdminController(adminService, materialService, categoryService, previewService, shareService, json));
        context.registerHttpController(new SharePublicController(shareService, materialService, previewService));
    }
}
