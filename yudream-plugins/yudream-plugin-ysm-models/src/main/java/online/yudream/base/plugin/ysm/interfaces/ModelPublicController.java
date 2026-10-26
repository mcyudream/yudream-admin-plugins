package online.yudream.base.plugin.ysm.interfaces;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.*;
import online.yudream.base.plugin.ysm.bootstrap.YsmModelsPlugin;
public final class ModelPublicController {
 private final ModelHttp http;public ModelPublicController(ModelHttp http){this.http=http;}
 @PluginHttpEndpoint(method="GET",path="/catalog",permission=YsmModelsPlugin.USER) public PluginHttpResponse catalog(PluginHttpRequest r){return http.list(r,1);}
 @PluginHttpEndpoint(method="GET",path="/catalog/{id}/preview",permission=YsmModelsPlugin.USER) public PluginHttpResponse preview(PluginHttpRequest r){return http.preview(r,true);}
 @PluginHttpEndpoint(method="GET",path="/bridge/manifest",wrapResult=false) public PluginHttpResponse manifest(PluginHttpRequest r){return http.manifest(r);}
 @PluginHttpEndpoint(method="GET",path="/bridge/state",wrapResult=false) public PluginHttpResponse state(PluginHttpRequest r){return http.state(r);}
 @PluginHttpEndpoint(method="GET",path="/download/bundle/{hash}",wrapResult=false) public PluginHttpResponse download(PluginHttpRequest r){return http.download(r);}
}
