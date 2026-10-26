package online.yudream.ysm.folia;

import online.yudream.ysm.model.ModelAsset;
import online.yudream.ysm.protocol.Protocol;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Blocking HTTP operations run only on the bridge worker. Credentials never enter asset URLs. */
final class BridgeClient implements AutoCloseable {
    private final URI base;private final String token;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    BridgeClient(String base,String token){this.base=URI.create(base.replaceAll("/+$","")+"/api/plugins/ysm-models/");this.token=token;
        if(token.length()<32)throw new IllegalArgumentException("bridge-token 需要至少 32 个字符");
        if(!Set.of("https","http").contains(this.base.getScheme()))throw new IllegalArgumentException("website-url 需要为 HTTP(S) 地址");}
    Protocol.Manifest manifest(UUID player){return get("bridge/manifest?uuid="+player,Protocol.Manifest.class);}
    Protocol.State state(Collection<UUID> players){return get("bridge/state?players="+String.join(",",players.stream().map(UUID::toString).toList()),Protocol.State.class);}
    private <T>T get(String path,Class<T> type){
        try{
            var request=HttpRequest.newBuilder(base.resolve(path)).header("X-Ysm-Bridge-Token",token).timeout(Duration.ofSeconds(20)).GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;
            try(var in=response.body()){if(response.statusCode()!=200)throw new IllegalStateException("网站桥接响应 "+response.statusCode());bytes=in.readNBytes(1024*1024+1);}
            if(bytes.length>1024*1024)throw new IllegalStateException("网站清单超过大小限制");
            T result=ModelAsset.JSON.fromJson(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),type);
            if(result==null)throw new IllegalStateException("网站清单为空");return result;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("网站请求已取消");}
        catch(Exception e){throw new IllegalStateException("网站桥接暂不可用",e);}
    }
    public void close(){http.shutdownNow();}
}
