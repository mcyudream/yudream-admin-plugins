package online.yudream.base.plugin.ysm.bootstrap;
import online.yudream.base.plugin.spi.annotation.*;
import online.yudream.base.plugin.spi.core.*;
import online.yudream.base.plugin.ysm.application.ModelService;
import online.yudream.base.plugin.ysm.infrastructure.*;
import online.yudream.base.plugin.ysm.interfaces.*;

@PluginSpec(code="ysm-models",name="玩家模型",version="0.2.0",description="皮肤站的通用模型上传、预览与装备附属")
@PluginPermissions({@PluginPermission(code=YsmModelsPlugin.USER,name="使用玩家模型",module="平台插件",description="管理自己的模型并装备到自己的角色"),@PluginPermission(code=YsmModelsPlugin.MANAGE,name="管理玩家模型",module="平台插件",description="维护服务器共享模型与启用状态")})
@PluginFrontend(moduleName="ysmModels",menuTitle="玩家模型",menuIcon="i-ri:box-3-fill",styles={"style.css"},routes={
 @PluginRoute(path="/platform/plugins/ysm-models",name="ysm-models-own",title="我的模型",icon="i-ri:archive-drawer-line",component="ysm-models/Home",permission=YsmModelsPlugin.USER,sort=10),
 @PluginRoute(path="/platform/plugins/ysm-models/catalog",name="ysm-models-catalog",title="服务器模型",icon="i-ri:archive-stack-line",component="ysm-models/Catalog",permission=YsmModelsPlugin.USER,sort=20),
 @PluginRoute(path="/platform/plugins/ysm-models/admin",name="ysm-models-admin",title="模型管理",icon="i-ri:settings-3-line",component="ysm-models/Admin",permission=YsmModelsPlugin.MANAGE,sort=30)
})
public final class YsmModelsPlugin implements YuDreamPlugin {
 public static final String USER="plugin:ysm-models:user",MANAGE="plugin:ysm-models:manage";
 public void onEnable(PluginContext context){
  var links=new AssetLinks(System.getenv("YSM_PUBLIC_BASE_URL"),System.getenv("YSM_BRIDGE_TOKEN"));
  var service=new ModelService(new ModelRepository(context.documents(),context.files()),SkinPort.create(context),links);
  var http=new ModelHttp(service,links);context.registerHttpController(new ModelUserController(http));context.registerHttpController(new ModelAdminController(http));context.registerHttpController(new ModelPublicController(http));
 }
}
