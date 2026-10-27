package online.yudream.base.plugin.ysm.interfaces;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.*;
import online.yudream.base.plugin.ysm.bootstrap.YsmModelsPlugin;
public final class ModelUserController {
 private final ModelHttp http;public ModelUserController(ModelHttp http){this.http=http;}
 @PluginHttpEndpoint(method="GET",path="/me/upload-policy",permission=YsmModelsPlugin.USER) public PluginHttpResponse policy(PluginHttpRequest r){return http.uploadPolicy(r);}
 @PluginHttpEndpoint(method="GET",path="/me/models",permission=YsmModelsPlugin.USER) public PluginHttpResponse list(PluginHttpRequest r){return http.list(r,0);}
 @PluginHttpEndpoint(method="POST",path="/me/models",permission=YsmModelsPlugin.USER) public PluginHttpResponse upload(PluginHttpRequest r){return http.upload(r,false);}
 @PluginHttpEndpoint(method="POST",path="/me/models/{id}/versions",permission=YsmModelsPlugin.USER) public PluginHttpResponse version(PluginHttpRequest r){return http.upload(r,true);}
 @PluginHttpEndpoint(method="GET",path="/me/models/{id}/preview",permission=YsmModelsPlugin.USER) public PluginHttpResponse preview(PluginHttpRequest r){return http.preview(r,false);}
 @PluginHttpEndpoint(method="PUT",path="/me/models/{id}",permission=YsmModelsPlugin.USER) public PluginHttpResponse rename(PluginHttpRequest r){return http.rename(r);}
 @PluginHttpEndpoint(method="DELETE",path="/me/models/{id}",permission=YsmModelsPlugin.USER) public PluginHttpResponse delete(PluginHttpRequest r){return http.delete(r,false);}
 @PluginHttpEndpoint(method="GET",path="/me/players",permission=YsmModelsPlugin.USER) public PluginHttpResponse players(PluginHttpRequest r){return http.players(r);}
 @PluginHttpEndpoint(method="PUT",path="/me/players/{uuid}/outfit",permission=YsmModelsPlugin.USER) public PluginHttpResponse equip(PluginHttpRequest r){return http.equip(r);}
}
