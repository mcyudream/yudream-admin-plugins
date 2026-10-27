package online.yudream.ysm.folia;

import online.yudream.ysm.model.ModelAsset;
import online.yudream.ysm.protocol.Protocol;
import java.net.*;
import java.net.http.*;
import javax.net.ssl.SSLException;
import com.google.gson.JsonParseException;
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
            try(var in=response.body()){if(response.statusCode()!=200)throw new Failure("HTTP "+response.statusCode()+(response.statusCode()==401||response.statusCode()==403?"，请核对网站与游戏服的桥接密钥":""));bytes=in.readNBytes(1024*1024+1);}
            if(bytes.length>1024*1024)throw new Failure("网站清单超过大小限制");
            T result=ModelAsset.JSON.fromJson(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),type);
            if(result==null)throw new Failure("网站清单为空");return result;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure("网站请求已取消");}
        catch(Exception e){throw new IllegalStateException("网站桥接暂不可用",e);}
    }
    /** Only known diagnostic text is logged: HTTP exceptions may contain credential-bearing URLs. */
    static String failure(Throwable error){
        for(Throwable e=error;e!=null;e=e.getCause()){
            if(e instanceof Failure)return e.getMessage();
            if(e instanceof HttpTimeoutException)return "网站请求超时";
            if(e instanceof ConnectException)return "无法连接网站，请检查地址、端口与网络";
            if(e instanceof UnknownHostException||e instanceof java.nio.channels.UnresolvedAddressException)return "网站域名解析失败";
            if(e instanceof SSLException)return "网站 TLS 连接失败，请检查证书";
            if(e instanceof JsonParseException)return "网站响应不是有效的模型 JSON，请检查网站地址及反向代理";
        }
        return "处理失败（"+error.getClass().getSimpleName()+"）";
    }
    static final class Failure extends IllegalStateException {Failure(String message){super(message);}}
    public void close(){http.shutdownNow();}
}
