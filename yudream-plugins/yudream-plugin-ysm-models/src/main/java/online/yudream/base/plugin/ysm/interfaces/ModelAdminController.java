package online.yudream.base.plugin.ysm.interfaces;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.*;
import online.yudream.base.plugin.ysm.bootstrap.YsmModelsPlugin;
public final class ModelAdminController {
 private final ModelHttp http;public ModelAdminController(ModelHttp http){this.http=http;}
 @PluginHttpEndpoint(method="GET",path="/admin/models",permission=YsmModelsPlugin.MANAGE) public PluginHttpResponse list(PluginHttpRequest r){return http.list(r,2);}
 @PluginHttpEndpoint(method="GET",path="/admin/models/{id}/preview",permission=YsmModelsPlugin.MANAGE) public PluginHttpResponse preview(PluginHttpRequest r){return http.adminPreview(r);}
 @PluginHttpEndpoint(method="PUT",path="/admin/models/{id}",permission=YsmModelsPlugin.MANAGE) public PluginHttpResponse manage(PluginHttpRequest r){return http.manage(r);}
 @PluginHttpEndpoint(method="DELETE",path="/admin/models/{id}",permission=YsmModelsPlugin.MANAGE) public PluginHttpResponse delete(PluginHttpRequest r){return http.delete(r,true);}
}
