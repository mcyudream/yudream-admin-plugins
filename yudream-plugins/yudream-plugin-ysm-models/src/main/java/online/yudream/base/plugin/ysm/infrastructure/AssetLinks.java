package online.yudream.base.plugin.ysm.infrastructure;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Separate short-lived download grants from the server bridge credential. */
public final class AssetLinks {
    private final String base;private final byte[] secret;
    public AssetLinks(String base,String secret){this.base=base==null?"":base.replaceAll("/+$","");this.secret=(secret==null?"":secret).getBytes(StandardCharsets.UTF_8);}
    public boolean configured(){return !base.isBlank()&&secret.length>=32;}
    public String url(String kind,String hash){if(!configured())throw new IllegalStateException("请配置 YSM_PUBLIC_BASE_URL 和至少 32 字符的 YSM_BRIDGE_TOKEN");long expires=System.currentTimeMillis()/1000+3600;return base+"/api/plugins/ysm-models/download/"+kind+"/"+hash+"?expires="+expires+"&sig="+sign(kind,hash,expires);}
    public boolean verify(String kind,String hash,long expires,String signature){long now=System.currentTimeMillis()/1000;return configured()&&expires>=now&&expires<=now+7200&&MessageDigest.isEqual(sign(kind,hash,expires).getBytes(StandardCharsets.US_ASCII),(signature==null?"":signature).getBytes(StandardCharsets.US_ASCII));}
    public boolean bridge(String supplied){return secret.length>=32&&MessageDigest.isEqual(secret,(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8));}
    private String sign(String kind,String hash,long expires){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal((kind+"\n"+hash+"\n"+expires).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
