package online.yudream.base.plugin.ysm.infrastructure;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;
import online.yudream.base.plugin.ysm.domain.BridgeSettings;
import online.yudream.ysm.model.ModelAsset;

/** Separate short-lived download grants from the server bridge credential. */
public final class AssetLinks {
    private static final String SETTINGS_KEY = "model-bridge-settings";
    private final PluginSecretStore secrets;
    private volatile BridgeSettings settings;
    public AssetLinks(String base,String secret){this(null,base,secret);}
    public AssetLinks(PluginSecretStore secrets,String base,String secret){
        this.secrets=secrets;
        this.settings=secrets==null?new BridgeSettings(base,secret):secrets.get(SETTINGS_KEY)
                .map(bytes->ModelAsset.JSON.fromJson(new String(bytes,StandardCharsets.UTF_8),BridgeSettings.class))
                .orElseGet(()->new BridgeSettings(base,secret));
    }
    public BridgeSettings settings(){return settings;}
    public synchronized BridgeSettings settings(BridgeSettings value){
        value.validated();
        if(secrets==null)throw new IllegalStateException("当前环境未启用插件密钥存储");
        // One encrypted value keeps the URL and credential together across restarts and failed writes.
        secrets.put(SETTINGS_KEY,ModelAsset.JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
        settings=value;return value;
    }
    private static boolean configured(BridgeSettings value){return !value.publicBaseUrl().isBlank()&&value.bridgeToken().length()>=32;}
    public boolean configured(){return configured(settings);}
    public String url(String kind,String hash){BridgeSettings value=settings;if(!configured(value))throw new IllegalStateException("请在玩家模型的“模型设置”页面配置网站地址和桥接密钥");long expires=System.currentTimeMillis()/1000+3600;return value.publicBaseUrl()+"/api/plugins/ysm-models/download/"+kind+"/"+hash+"?expires="+expires+"&sig="+sign(value,kind,hash,expires);}
    public boolean verify(String kind,String hash,long expires,String signature){BridgeSettings value=settings;long now=System.currentTimeMillis()/1000;return configured(value)&&expires>=now&&expires<=now+7200&&MessageDigest.isEqual(sign(value,kind,hash,expires).getBytes(StandardCharsets.US_ASCII),(signature==null?"":signature).getBytes(StandardCharsets.US_ASCII));}
    public boolean bridge(String supplied){BridgeSettings value=settings;return value.bridgeToken().length()>=32&&MessageDigest.isEqual(value.bridgeToken().getBytes(StandardCharsets.UTF_8),(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8));}
    private String sign(BridgeSettings value,String kind,String hash,long expires){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(value.bridgeToken().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal((kind+"\n"+hash+"\n"+expires).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
